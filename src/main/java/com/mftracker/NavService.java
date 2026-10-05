package com.mftracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Fetches live NAVs. Primary source: AMFI's NAVAll.txt (official, one download covers every scheme).
 * Fallback: mfapi.in per scheme code. Also matches your funds to AMFI scheme codes.
 */
@Service
public class NavService {
    private static final Logger log = LoggerFactory.getLogger(NavService.class);
    private static final DateTimeFormatter AMFI_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MFAPI_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final Set<String> NOISE = Set.of("fund", "plan", "option", "growth", "regular", "direct", "scheme", "the");
    private static final List<String> BAD_OPTIONS = List.of("idcw", "dividend", "bonus", "withdrawal", "payout", "reinvest", "distribution");
    // AMFI currently leaves plan/option columns blank for Motilal Oswal Multi Cap Fund.
    // Restore the published plan/option labels so the four rows can be matched safely.
    private static final Map<String, String> AMFI_OPTION_FALLBACKS = Map.of(
            "152649", "Regular Plan IDCW", "152650", "Regular Plan Growth",
            "152651", "Direct Plan Growth", "152652", "Direct Plan IDCW");

    public record SchemeNav(String code, String name, BigDecimal nav, LocalDate date) { }
    public record RefreshResult(boolean ok, String source, int navsUpdated, int autoMatched, int unmapped, String message) { }

    private final FundRepository funds;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    @Value("${mftracker.amfi-url}") private String amfiUrl;
    @Value("${mftracker.mfapi-url}") private String mfapiUrl;
    @Value("${mftracker.nav-tolerance:0.15}") private double tolerance;

    private volatile Map<String, SchemeNav> schemes = Map.of();
    private volatile LocalDateTime lastRefresh;

    public NavService(FundRepository funds, ObjectMapper mapper) { this.funds = funds; this.mapper = mapper; }

    public LocalDateTime lastRefresh() { return lastRefresh; }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { CompletableFuture.runAsync(this::refresh); }

    @Scheduled(cron = "0 */30 * * * *", zone = "Asia/Kolkata")
    public void scheduled() { refresh(); }

    // ---------------------------------------------------------------- refresh

    public synchronized RefreshResult refresh() {
        boolean amfiOk = loadAmfi();
        int updated = 0, matched = 0;
        String source = amfiOk ? "AMFI" : "MFAPI";
        List<Fund> all = funds.findAll();
        for (Fund f : all) {
            if (amfiOk && f.mappingStatus == Fund.MappingStatus.UNMAPPED && f.amfiCode == null) {
                SchemeNav m = autoMatch(f);
                if (m != null) {
                    if (f.plan == Fund.Plan.UNKNOWN) f.plan = inferredPlan(f);
                    f.amfiCode = m.code(); f.amfiSchemeName = m.name(); f.mappingStatus = Fund.MappingStatus.AUTO;
                    matched++;
                }
            }
            if (f.amfiCode == null) continue;
            SchemeNav s = amfiOk ? schemes.get(f.amfiCode) : fetchLatestFromMfapi(f.amfiCode);
            if (s != null && applyNav(f, s, amfiOk ? "AMFI" : "MFAPI")) updated++;
        }
        funds.saveAll(all);
        int unmapped = (int) all.stream().filter(f -> f.amfiCode == null).count();
        if (amfiOk || updated > 0) lastRefresh = LocalDateTime.now();
        String msg = (amfiOk ? "Loaded " + schemes.size() + " schemes from AMFI. " : "AMFI download failed; used mfapi.in fallback. ")
                + updated + " NAVs updated, " + matched + " funds auto-linked, " + unmapped + " funds still need a scheme code.";
        log.info(msg);
        return new RefreshResult(amfiOk || updated > 0, source, updated, matched, unmapped, msg);
    }

    private boolean applyNav(Fund f, SchemeNav s, String source) {
        if (s.nav() == null || s.date() == null) return false;
        f.latestNav = s.nav(); f.navDate = s.date(); f.navSource = source; f.navUpdatedAt = LocalDateTime.now();
        return true;
    }

    /** Refresh a single fund. Uses mfapi.in (fresh, one small call), falling back to the last AMFI download. */
    public SchemeNav refreshOne(Fund f) {
        if (f.amfiCode == null && !schemes.isEmpty()) {
            SchemeNav m = autoMatch(f);
            if (m != null) {
                if (f.plan == Fund.Plan.UNKNOWN) f.plan = inferredPlan(f);
                f.amfiCode = m.code(); f.amfiSchemeName = m.name(); f.mappingStatus = Fund.MappingStatus.AUTO;
            }
        }
        if (f.amfiCode == null)
            throw new IllegalArgumentException("This fund isn't linked to an AMFI scheme code yet. Link it under Funds & scheme codes.");
        SchemeNav s = fetchLatestFromMfapi(f.amfiCode);
        String source = "MFAPI";
        SchemeNav cached = schemes.get(f.amfiCode);
        if (s == null || (cached != null && cached.date() != null && cached.date().isAfter(s.date()))) {
            if (cached != null) { s = cached; source = "AMFI"; }
        }
        if (s == null) throw new IllegalArgumentException("Couldn't fetch the NAV for scheme " + f.amfiCode + ". Check your internet connection and try again.");
        applyNav(f, s, source);
        return s;
    }

    private List<SchemeNav> mfapiSearch(String q, int limit) {
        try {
            JsonNode arr = mfapiGet("/search?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
            List<SchemeNav> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String name = n.path("schemeName").asText();
                if (!isGrowthOption(norm(name))) continue;
                out.add(new SchemeNav(n.path("schemeCode").asText(), name, null, null));
                if (out.size() >= limit) break;
            }
            return out;
        } catch (Exception e) {
            log.warn("mfapi.in search failed: {}", e.toString());
            return List.of();
        }
    }

    /** Link a fund to a code the user chose, and pull its NAV immediately. */
    public void applyScheme(Fund f, String code) {
        SchemeNav s = schemes.get(code);
        if (s == null && !schemes.isEmpty()) throw new IllegalArgumentException("Scheme code " + code + " not found in AMFI list");
        if (s == null) s = fetchLatestFromMfapi(code);
        if (s == null) throw new IllegalArgumentException("Could not look up scheme code " + code);
        f.amfiCode = code; f.amfiSchemeName = s.name(); f.mappingStatus = Fund.MappingStatus.CONFIRMED;
        applyNav(f, s, "AMFI");
    }

    // ---------------------------------------------------------------- AMFI

    private boolean loadAmfi() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(amfiUrl)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                    .header("Accept", "text/plain,*/*").GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
            Map<String, SchemeNav> parsed = new HashMap<>(20000);
            for (String line : resp.body().split("\\R")) {
                String[] p = line.split(";", -1);
                if (p.length < 6 || !p[0].trim().replace("\uFEFF", "").matches("\\d+")) continue;
                try {
                    // AMFI added separate plan and option columns in its current NAVAll.txt:
                    // code, two ISIN columns, scheme name, plan, option, NAV, date.
                    // Retain support for the older six-column version as well.
                    int navIndex = p.length >= 8 ? 6 : 4;
                    int dateIndex = navIndex + 1;
                    String code = p[0].trim().replace("\uFEFF", "");
                    String name = p[3].trim();
                    if (p.length >= 8) {
                        // Current AMFI files keep plan and option in separate columns.
                        // Include them in the candidate name so Growth is recognized and
                        // Direct/Regular plans can be distinguished during auto-matching.
                        if (!p[4].isBlank()) name += " " + p[4].trim();
                        if (!p[5].isBlank()) name += " " + p[5].trim();
                        if (p[4].isBlank() && p[5].isBlank()) {
                            String option = AMFI_OPTION_FALLBACKS.get(code);
                            if (option != null && name.equalsIgnoreCase("Motilal Oswal Multi Cap Fund")) name += " " + option;
                        }
                    }
                    LocalDate d = LocalDate.parse(p[dateIndex].trim(), AMFI_DATE);
                    parsed.put(code, new SchemeNav(code, name, new BigDecimal(p[navIndex].trim()), d));
                } catch (Exception ignored) { /* "N.A." NAVs or odd rows */ }
            }
            if (parsed.size() < 1000) throw new IllegalStateException("Unexpected AMFI file (" + parsed.size() + " rows)");
            schemes = parsed;
            return true;
        } catch (Exception e) {
            log.warn("AMFI NAV download failed: {}", e.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------- mfapi.in

    private JsonNode mfapiGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(mfapiUrl + path)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (mf-tracker)").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
        return mapper.readTree(resp.body());
    }

    private SchemeNav fetchLatestFromMfapi(String code) {
        try {
            JsonNode root = mfapiGet("/" + code + "/latest");
            JsonNode d = root.path("data").path(0);
            if (d.isMissingNode()) return null;
            return new SchemeNav(code, root.path("meta").path("scheme_name").asText(code),
                    new BigDecimal(d.path("nav").asText()), LocalDate.parse(d.path("date").asText(), MFAPI_DATE));
        } catch (Exception e) {
            log.warn("mfapi.in lookup failed for {}: {}", code, e.toString());
            return null;
        }
    }

    /** NAV applicable on or before the given date (for pre-filling a purchase). */
    public Optional<SchemeNav> navOn(String code, LocalDate date) {
        try {
            JsonNode root = mfapiGet("/" + code);
            SchemeNav best = null;
            for (JsonNode d : root.path("data")) {          // newest first
                LocalDate dd = LocalDate.parse(d.path("date").asText(), MFAPI_DATE);
                if (!dd.isAfter(date)) {
                    best = new SchemeNav(code, root.path("meta").path("scheme_name").asText(code),
                            new BigDecimal(d.path("nav").asText()), dd);
                    break;
                }
            }
            return Optional.ofNullable(best);
        } catch (Exception e) {
            log.warn("Historical NAV lookup failed for {}: {}", code, e.toString());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- recent NAV history (for the daily pick)

    public record NavPoint(LocalDate date, BigDecimal nav) { }
    private record CachedHistory(java.time.Instant at, List<NavPoint> points) { }
    private final Map<String, CachedHistory> historyCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Last ~60 NAVs for a scheme, newest first (cached for 3 hours). */
    public List<NavPoint> history(String code) {
        CachedHistory c = historyCache.get(code);
        if (c != null && c.at().isAfter(java.time.Instant.now().minus(Duration.ofHours(3)))) return c.points();
        try {
            JsonNode root = mfapiGet("/" + code);
            List<NavPoint> pts = new ArrayList<>();
            for (JsonNode d : root.path("data")) {
                try {
                    pts.add(new NavPoint(LocalDate.parse(d.path("date").asText(), MFAPI_DATE), new BigDecimal(d.path("nav").asText())));
                } catch (Exception ignored) { /* skip a bad row */ }
                if (pts.size() >= 60) break;
            }
            historyCache.put(code, new CachedHistory(java.time.Instant.now(), pts));
            return pts;
        } catch (Exception e) {
            log.warn("NAV history failed for {}: {}", code, e.toString());
            return c != null ? c.points() : List.of();
        }
    }

    public void clearHistoryCache() { historyCache.clear(); }

    // ---------------------------------------------------------------- matching / search

    static String norm(String s) {
        if (s == null || s.isBlank()) return "";
        s = s.toLowerCase(Locale.ROOT).replace("&", " and ")
                .replace("inveso", "invesco").replace("enengy", "energy").replaceAll("\\bboa\\b", "bank of india")
                .replaceAll("\\b(large|mid|small|multi|flexi)\\s*-?\\s*cap\\b", "$1cap");
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Set<String> tokens(String s, boolean dropNoise) {
        // CAMS may append a legacy name in parentheses, sometimes without "known as".
        // Those historical words describe the same scheme and must not block an AMFI match.
        if (s != null) s = s.replaceAll("(?i)\\s*\\(\\s*formerly(?:\\s+known\\s+as)?\\s+[^)]*\\)", " ");
        Set<String> t = new LinkedHashSet<>(Arrays.asList(norm(s).split(" ")));
        t.remove("");
        if (dropNoise) t.removeAll(NOISE);
        return t;
    }

    private static boolean isGrowthOption(String candNorm) {
        return candNorm.contains("growth") && BAD_OPTIONS.stream().noneMatch(candNorm::contains);
    }

    private static Fund.Plan inferredPlan(Fund f) {
        if (f.plan != Fund.Plan.UNKNOWN) return f.plan;
        String name = " " + norm(f.schemeName) + " ";
        if (name.contains(" direct ")) return Fund.Plan.DIRECT;
        if (name.contains(" regular ")) return Fund.Plan.REGULAR;
        // CAMS and several other registrars omit the word "Regular" for regular plans,
        // while they explicitly label Direct plans. Treat an unqualified plan as Regular
        // so otherwise-identical Direct/Regular AMFI entries do not remain ambiguous.
        return Fund.Plan.REGULAR;
    }

    /** Candidates whose name contains every word of the fund's name, growth option, matching plan, best fit first. */
    List<SchemeNav> candidates(Fund f, boolean usePlan) {
        Set<String> mine = tokens(f.schemeName, true);
        if (mine.isEmpty() || schemes.isEmpty()) return List.of();
        List<Map.Entry<SchemeNav, Double>> out = new ArrayList<>();
        Fund.Plan plan = inferredPlan(f);
        for (SchemeNav s : schemes.values()) {
            String n = norm(s.name());
            if (!isGrowthOption(n)) continue;
            Set<String> ct = tokens(s.name(), true);
            if (!ct.containsAll(mine)) continue;
            boolean direct = n.contains("direct");
            if (usePlan && plan == Fund.Plan.DIRECT && !direct) continue;
            if (usePlan && plan == Fund.Plan.REGULAR && direct) continue;
            out.add(Map.entry(s, (double) mine.size() / ct.size()));
        }
        out.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return out.stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private boolean navClose(Fund f, SchemeNav s) {
        if (f.sheetNav == null || f.sheetNav.signum() == 0) return true;
        return Math.abs(s.nav().doubleValue() / f.sheetNav.doubleValue() - 1) <= tolerance;
    }

    private SchemeNav autoMatch(Fund f) {
        List<SchemeNav> ok = candidates(f, true).stream().filter(s -> navClose(f, s)).limit(2).toList();
        if (ok.isEmpty()) return null;
        if (ok.size() == 1) return ok.get(0);
        // two plausible candidates: accept the leader only if it is a clearly tighter name match
        double p0 = (double) tokens(f.schemeName, true).size() / tokens(ok.get(0).name(), true).size();
        double p1 = (double) tokens(f.schemeName, true).size() / tokens(ok.get(1).name(), true).size();
        return p0 > p1 + 0.0001 ? ok.get(0) : null;
    }

    /** Free-text search over the AMFI list (growth options only). */
    public List<SchemeNav> search(String q, int limit) {
        Set<String> want = tokens(q, false);
        want.removeAll(Set.of("growth", "fund", "plan", "option"));
        if (want.isEmpty()) return List.of();
        if (schemes.isEmpty()) return mfapiSearch(String.join(" ", want), limit);   // AMFI list unavailable
        return schemes.values().stream()
                .filter(s -> { String n = norm(s.name()); return isGrowthOption(n) && Arrays.asList(n.split(" ")).containsAll(want); })
                .sorted(Comparator.comparingInt(s -> s.name().length()))
                .limit(limit).collect(Collectors.toList());
    }

    public List<SchemeNav> suggestionsFor(Fund f) {
        List<SchemeNav> c = candidates(f, false);
        if (c.isEmpty()) {   // also the case when the AMFI list isn't loaded   // looser: first two distinctive words
            Set<String> t = tokens(f.schemeName, true);
            String q = String.join(" ", t.stream().limit(2).toList());
            return search(q, 15);
        }
        return c.stream().limit(15).toList();
    }

    public boolean listLoaded() { return !schemes.isEmpty(); }
}
