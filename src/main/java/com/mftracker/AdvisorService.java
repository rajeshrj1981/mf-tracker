package com.mftracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Daily "where could I put Rs 1,000 today" idea.
 * Rule: among your linked funds with a fresh NAV that are not already an outsized part of the portfolio,
 * pick the one with the strongest 7-day NAV return. If all eligible returns are negative, this is a relative
 * screen result only; the commentary must make the negative return clear. If an Anthropic API key is configured,
 * Claude writes the commentary; the ranking itself is plain arithmetic.
 */
@Service
public class AdvisorService {
    private static final Logger log = LoggerFactory.getLogger(AdvisorService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SYSTEM = "You are a careful mutual-fund research assistant for an Indian retail investor. "
            + "You are not a licensed financial advisor. Use ONLY the numbers provided; never invent data or predict returns. "
            + "Write 3 to 4 plain sentences (under 90 words): explain this fund's relative 7-day NAV result, what the one-month return "
            + "and the number of up days say about the recent trend, and one honest caution. If the 7-day return is negative, explicitly "
            + "say so and describe the fund only as the least-negative eligible candidate, not as a fund that is rising. Short-term "
            + "momentum is a weak predictor. No headings, no bullet points, no guarantees, no instructions to buy.";

    public record Row(Long fundId, String schemeName, String fundHouse, String category, String plan, String code,
                      BigDecimal nav, LocalDate navDate, Double oneWeekPct, Double oneMonthPct, int upDays, int tradingDays,
                      double weightPct, String note) { }
    public record Today(LocalDate date, BigDecimal amount, Row pick, String message, String rationale, String source,
                        List<Row> ranking, boolean aiEnabled, LocalDateTime generatedAt) { }
    public record HistoryRow(LocalDate date, Long fundId, String schemeName, BigDecimal amount, BigDecimal navAtPick, BigDecimal navNow,
                             Double sincePct, Double oneWeekPct, String source) { }

    private final FundRepository funds;
    private final DailyPickRepository picks;
    private final NavService nav;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Value("${mftracker.advisor.daily-amount:1000}") private BigDecimal dailyAmount;
    @Value("${mftracker.advisor.max-weight:0.10}") private double maxWeight;
    @Value("${anthropic.api-key:}") private String apiKey;
    @Value("${anthropic.model:claude-haiku-4-5-20251001}") private String model;

    public AdvisorService(FundRepository funds, DailyPickRepository picks, NavService nav, ObjectMapper mapper) {
        this.funds = funds; this.picks = picks; this.nav = nav; this.mapper = mapper;
    }

    // ---------------------------------------------------------------- public API

    public synchronized Today today(boolean regenerate, String owner) {
        LocalDate today = LocalDate.now(IST);
        if (!"rajesh".equals(owner)) {
            DailyPick temporary = new DailyPick();
            generate(temporary, today, owner);
            return view(temporary);
        }
        Optional<DailyPick> existing = picks.findByPickDate(today);
        if (existing.isPresent() && !regenerate) return view(existing.get());
        if (regenerate) nav.clearHistoryCache();
        DailyPick p = existing.orElseGet(DailyPick::new);
        generate(p, today, owner);
        return view(picks.save(p));
    }

    public List<HistoryRow> history(String owner) {
        if (!"rajesh".equals(owner)) return List.of();
        List<DailyPick> list = picks.findTop50ByOrderByPickDateDesc();
        List<Long> ids = list.stream().map(p -> p.fundId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Fund> byId = funds.findAllById(ids).stream().collect(Collectors.toMap(f -> f.id, f -> f));
        List<HistoryRow> out = new ArrayList<>();
        for (DailyPick p : list) {
            if (p.fundId == null) continue;            // days with no suggestion
            Fund f = byId.get(p.fundId);
            BigDecimal now = f == null ? null : f.latestNav;
            Double since = (now == null || p.navAtPick == null || p.navAtPick.signum() == 0) ? null
                    : round2((now.doubleValue() / p.navAtPick.doubleValue() - 1) * 100);
            out.add(new HistoryRow(p.pickDate, p.fundId, p.fundName, p.amount, p.navAtPick, now, since, p.oneWeekPct, p.source));
        }
        return out;
    }

    // ---------------------------------------------------------------- generation

    private void generate(DailyPick p, LocalDate today, String owner) {
        List<Fund> all = funds.findAllWithPurchasesByOwner(owner);
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> valueByCode = new LinkedHashMap<>();
        Map<String, Fund> byCode = new LinkedHashMap<>();
        for (Fund f : all) {
            if (f.latestNav == null) continue;
            BigDecimal units = f.purchases.stream().filter(x -> x.ownerUsername.equals(owner)).map(x -> x.units).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal value = units.multiply(f.latestNav);
            total = total.add(value);
            if (f.amfiCode != null) { valueByCode.merge(f.amfiCode, value, BigDecimal::add); byCode.putIfAbsent(f.amfiCode, f); }
        }
        p.pickDate = today; p.amount = dailyAmount; p.createdAt = LocalDateTime.now(IST);
        p.fundId = null; p.fundName = null; p.navAtPick = null; p.oneWeekPct = null; p.oneMonthPct = null;
        p.source = "RULE"; p.rationale = null;

        if (byCode.isEmpty()) {
            p.message = "None of your funds is linked to an AMFI scheme code yet, so there are no NAVs to compare. "
                    + "Link them under Funds & scheme codes.";
            p.rankingJson = "[]";
            return;
        }

        // fetch recent NAV history for every linked scheme, 8 at a time
        Map<String, List<NavService.NavPoint>> hist = new java.util.concurrent.ConcurrentHashMap<>();
        ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            CompletableFuture.allOf(byCode.keySet().stream()
                    .map(c -> CompletableFuture.runAsync(() -> hist.put(c, nav.history(c)), ex))
                    .toArray(CompletableFuture[]::new)).join();
        } finally { ex.shutdown(); }

        LocalDate newest = hist.values().stream().filter(h -> !h.isEmpty()).map(h -> h.get(0).date())
                .max(Comparator.naturalOrder()).orElse(null);

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Fund> e : byCode.entrySet()) {
            Fund f = e.getValue();
            List<NavService.NavPoint> h = hist.getOrDefault(e.getKey(), List.of());
            double weight = total.signum() == 0 ? 0
                    : valueByCode.get(e.getKey()).multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP).doubleValue();
            if (h.size() < 2 || newest == null) {
                rows.add(new Row(f.id, f.schemeName, f.fundHouse, f.category, f.plan.name(), e.getKey(), f.latestNav, f.navDate,
                        null, null, 0, 0, weight, "NAV history unavailable"));
                continue;
            }
            NavService.NavPoint latest = h.get(0);
            Double w1 = ret(h, 7), m1 = ret(h, 30);
            int up = 0, n = 0;
            LocalDate from = latest.date().minusDays(7);
            for (int i = 0; i < h.size() - 1 && h.get(i).date().isAfter(from); i++) {
                n++;
                if (h.get(i).nav().compareTo(h.get(i + 1).nav()) > 0) up++;
            }
            String note = null;
            if (latest.date().isBefore(newest.minusDays(5))) note = "NAV not up to date (" + latest.date() + ")";
            else if (w1 == null) note = "Less than a week of history";
            else if (weight > maxWeight * 100) note = String.format(Locale.ENGLISH, "Already %.1f%% of your portfolio", weight);
            rows.add(new Row(f.id, f.schemeName, f.fundHouse, f.category, f.plan.name(), e.getKey(), latest.nav(), latest.date(),
                    w1, m1, up, n, weight, note));
        }

        List<Row> eligible = rows.stream().filter(r -> r.note() == null)
                .sorted(Comparator.comparing(Row::oneWeekPct).reversed()).toList();
        List<Row> skipped = rows.stream().filter(r -> r.note() != null)
                .sorted(Comparator.comparing(Row::schemeName)).toList();
        List<Row> ranking = new ArrayList<>(eligible);
        ranking.addAll(skipped);
        try { p.rankingJson = mapper.writeValueAsString(ranking); } catch (Exception e) { p.rankingJson = "[]"; }

        Row best = eligible.isEmpty() ? null : eligible.get(0);
        if (best == null) {
            p.message = "No fund has enough recent NAV history to qualify for today's screen.";
            return;
        }
        p.fundId = best.fundId(); p.fundName = best.schemeName(); p.navAtPick = best.nav();
        p.oneWeekPct = best.oneWeekPct(); p.oneMonthPct = best.oneMonthPct();
        p.message = "Best 7-day NAV return among " + eligible.size() + " eligible funds with a fresh NAV"
                + (best.oneWeekPct() < 0 ? "; its return is still negative." : ".");
        p.rationale = template(best, eligible.size());
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                p.rationale = askClaude(best, eligible.stream().limit(5).toList(), eligible.size());
                p.source = "CLAUDE";
            } catch (Exception e) {
                log.warn("Claude commentary failed, using rule-based text: {}", e.toString());
            }
        }
    }

    private static Double ret(List<NavService.NavPoint> h, int days) {
        NavService.NavPoint latest = h.get(0);
        LocalDate target = latest.date().minusDays(days);
        for (NavService.NavPoint p : h) {
            if (!p.date().isAfter(target) && p.nav().signum() > 0)
                return round2((latest.nav().doubleValue() / p.nav().doubleValue() - 1) * 100);
        }
        return null;
    }

    private static double round2(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue(); }

    private String template(Row r, int compared) {
        String month = r.oneMonthPct() == null ? "has no one-month figure yet"
                : String.format(Locale.ENGLISH, "is %+.2f%% over the past month", r.oneMonthPct());
        String performanceContext = r.oneWeekPct() < 0
                ? "It is the least-negative eligible candidate; its NAV still fell over the week."
                : "It had the strongest positive 7-day return in the screen.";
        return String.format(Locale.ENGLISH,
                "%s returned %+.2f%% over the past 7 days (latest NAV %s on %s), the best relative result among %d eligible funds. "
                        + "%s It rose on %d of the last %d trading days and %s. This is a screen on past NAV moves; short-term momentum is a weak predictor, not a reason by itself to invest.",
                r.schemeName(), r.oneWeekPct(), r.nav().stripTrailingZeros().toPlainString(), r.navDate(), compared,
                performanceContext, r.upDays(), r.tradingDays(), month);
    }

    // ---------------------------------------------------------------- Claude

    private String askClaude(Row pick, List<Row> top, int compared) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("amountInr", dailyAmount);
        data.put("rule", "Pick = highest 7-day NAV return among funds with a fresh NAV that are each under "
                + Math.round(maxWeight * 100) + "% of the portfolio.");
        data.put("fundsCompared", compared);
        data.put("pick", pick);
        data.put("topFive", top);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 400);
        body.put("system", SYSTEM);
        ArrayNode msgs = body.putArray("messages");
        ObjectNode m = msgs.addObject();
        m.put("role", "user");
        m.put("content", "Data (JSON):\n" + mapper.writeValueAsString(data) + "\n\nWrite the commentary.");

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(45))
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IllegalStateException("Anthropic API HTTP " + resp.statusCode() + ": " + resp.body());
        JsonNode root = mapper.readTree(resp.body());
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : root.path("content")) if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
        String text = sb.toString().trim();
        if (text.isEmpty()) throw new IllegalStateException("Empty response from Claude");
        return text;
    }

    // ---------------------------------------------------------------- view

    private Today view(DailyPick p) {
        List<Row> ranking;
        try { ranking = mapper.readValue(p.rankingJson == null ? "[]" : p.rankingJson, new TypeReference<List<Row>>() { }); }
        catch (Exception e) { ranking = List.of(); }
        Row pick = ranking.stream().filter(r -> p.fundId != null && Objects.equals(r.fundId(), p.fundId)).findFirst().orElse(null);
        return new Today(p.pickDate, p.amount, pick, p.message, p.rationale, p.source, ranking,
                apiKey != null && !apiKey.isBlank(), p.createdAt);
    }
}
