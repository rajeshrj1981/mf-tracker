package com.mftracker;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    public record FundRequest(@NotBlank String schemeName, String fundHouse, String category, String plan, String amfiCode) { }
    public record PurchaseEmailRequest(@NotBlank String email) { }
    public record PurchaseRequest(@NotNull Long fundId, @NotNull LocalDate date, @NotNull @Positive BigDecimal amount,
                                  @Positive BigDecimal units, @Positive BigDecimal nav, String broker, String remark) { }
    public record FundRow(Long id, String schemeName, String fundHouse, String category, String plan, String amfiCode,
                          String amfiSchemeName, String mappingStatus, BigDecimal latestNav, LocalDate navDate, String navSource,
                          int purchases) { }

    private final FundRepository funds;
    private final PurchaseRepository purchases;
    private final DailyPickRepository dailyPicks;
    private final PortfolioService portfolio;
    private final NavService nav;
    private final PurchaseEmailParser purchaseEmailParser;
    private final CasImportService casImport;

    public ApiController(FundRepository funds, PurchaseRepository purchases, DailyPickRepository dailyPicks, PortfolioService portfolio, NavService nav,
                         PurchaseEmailParser purchaseEmailParser, CasImportService casImport) {
        this.funds = funds; this.purchases = purchases; this.dailyPicks = dailyPicks; this.portfolio = portfolio; this.nav = nav;
        this.purchaseEmailParser = purchaseEmailParser;
        this.casImport = casImport;
    }

    // ---------------------------------------------------------------- portfolio
    @GetMapping("/portfolio")
    public PortfolioService.Portfolio portfolio(@RequestParam(defaultValue = "none") String groupBy, HttpSession session) {
        return portfolio.build(groupBy, user(session));
    }

    @GetMapping("/strategy/summary")
    public PortfolioService.StrategySummary strategySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, HttpSession session) {
        return portfolio.strategySummary(from, to, user(session));
    }

    @PostMapping("/nav/refresh")
    public NavService.RefreshResult refresh() { return nav.refresh(); }

    /** Clear all investment data for both accounts, plus the saved daily-pick history. */
    @DeleteMapping("/data")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Long> clearData() {
        long purchaseCount = purchases.count();
        long fundCount = funds.count();
        long dailyPickCount = dailyPicks.count();
        purchases.deleteAllInBatch();
        dailyPicks.deleteAllInBatch();
        funds.deleteAllInBatch();
        return Map.of("purchasesDeleted", purchaseCount, "fundsDeleted", fundCount, "dailyPicksDeleted", dailyPickCount);
    }

    // ---------------------------------------------------------------- funds
    @GetMapping("/funds")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<FundRow> funds(HttpSession session) {
        String owner = user(session);
        return funds.findAll().stream()
                .sorted(Comparator.comparing((Fund f) -> String.valueOf(f.fundHouse)).thenComparing(f -> f.schemeName))
                .map(f -> row(f, owner)).toList();
    }

    @PostMapping("/funds")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public FundRow createFund(@Valid @RequestBody FundRequest r, HttpSession session) {
        Fund f = new Fund();
        copy(r, f);
        if (f.amfiCode != null) nav.applyScheme(f, f.amfiCode);
        return row(funds.save(f), user(session));
    }

    @PutMapping("/funds/{id}")
    @org.springframework.transaction.annotation.Transactional
    public FundRow updateFund(@PathVariable Long id, @Valid @RequestBody FundRequest r, HttpSession session) {
        Fund f = fund(id);
        String oldCode = f.amfiCode;
        copy(r, f);
        if (f.amfiCode != null && !f.amfiCode.equals(oldCode)) nav.applyScheme(f, f.amfiCode);
        if (f.amfiCode == null) { f.amfiSchemeName = null; f.mappingStatus = Fund.MappingStatus.UNMAPPED; }
        return row(funds.save(f), user(session));
    }

    @DeleteMapping("/funds/{id}")
    public ResponseEntity<Void> deleteFund(@PathVariable Long id) {
        funds.delete(fund(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/funds/{id}/refresh-nav")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> refreshFund(@PathVariable Long id) {
        Fund f = fund(id);
        NavService.SchemeNav s = nav.refreshOne(f);
        funds.save(f);
        return Map.of("message", f.schemeName + ": NAV " + s.nav() + " as on " + s.date(), "nav", s.nav(), "date", s.date().toString());
    }

    @GetMapping("/funds/{id}/suggestions")
    public List<NavService.SchemeNav> suggestions(@PathVariable Long id) { return nav.suggestionsFor(fund(id)); }

    @GetMapping("/schemes/search")
    public List<NavService.SchemeNav> search(@RequestParam String q) {
        return nav.search(q, 25);
    }

    @GetMapping("/funds/{id}/nav")
    public NavService.SchemeNav navOnDate(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Fund f = fund(id);
        if (f.amfiCode == null) throw new IllegalArgumentException("Link this fund to an AMFI scheme code first");
        return nav.navOn(f.amfiCode, date).orElseThrow(() -> new IllegalArgumentException("No NAV found on or before " + date));
    }

    @PostMapping("/purchases/parse-email")
    public PurchaseEmailParser.Result parsePurchaseEmail(@Valid @RequestBody PurchaseEmailRequest request) {
        return purchaseEmailParser.parse(request.email());
    }

    @PostMapping(value = "/cas/parse", consumes = "multipart/form-data")
    public CasImportService.Preview parseCas(@RequestPart("file") MultipartFile file,
                                              @RequestPart(value = "password", required = false) String password) {
        return casImport.parse(file, password);
    }

    public record CasImportRequest(@NotNull List<CasImportService.CasTransaction> transactions) { }

    @PostMapping("/cas/import")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> importCas(@Valid @RequestBody CasImportRequest request, HttpSession session) {
        String owner = user(session);
        if (request.transactions().isEmpty()) throw new IllegalArgumentException("The CAMS statement contains no purchase transactions to import.");

        // CAMS is the temporary testing account: each upload replaces its previous portfolio.
        // Keep Rajesh's purchases and all other account data untouched.
        if (owner.equals("cams")) purchases.deleteByOwnerUsername(owner);

        int imported = 0, duplicates = 0;
        for (CasImportService.CasTransaction tx : request.transactions()) {
            if (tx.schemeName() == null || tx.schemeName().isBlank() || tx.date() == null || tx.amount() == null || tx.amount().signum() <= 0)
                throw new IllegalArgumentException("Each row needs a scheme, date, and positive amount.");
            if (tx.date().isAfter(LocalDate.now())) throw new IllegalArgumentException("CAS contains a future transaction date.");
            String type = tx.transactionType() == null ? "" : tx.transactionType().toLowerCase(Locale.ROOT);
            if (!type.isBlank() && !(type.contains("purchase") || type.contains("sip"))) { duplicates++; continue; }
            String fundHouseKey = normalize(tx.fundHouse());
            Fund f = funds.findAll().stream()
                    .filter(x -> normalize(x.schemeName).equals(normalize(tx.schemeName())))
                    .filter(x -> fundHouseKey.isBlank() || normalize(x.fundHouse).equals(fundHouseKey))
                    .findFirst().orElse(null);
            if (f == null) {
                f = new Fund(); f.schemeName = tx.schemeName().trim(); f.fundHouse = trim(tx.fundHouse()); f.plan = Fund.Plan.UNKNOWN;
                f.mappingStatus = Fund.MappingStatus.UNMAPPED; f = funds.save(f);
            }
            BigDecimal units = tx.units(), navValue = tx.nav();
            if (units == null && navValue != null && navValue.signum() > 0)
                units = tx.amount().divide(navValue, 4, RoundingMode.HALF_UP);
            if (navValue == null && units != null && units.signum() > 0)
                navValue = tx.amount().divide(units, 4, RoundingMode.HALF_UP);
            if (units == null || navValue == null || units.signum() <= 0 || navValue.signum() <= 0)
                throw new IllegalArgumentException("A row for " + tx.schemeName() + " is missing both NAV and units. Edit the CAS transaction details or add it manually.");
            BigDecimal amount = tx.amount().setScale(2, RoundingMode.HALF_UP);
            units = units.setScale(4, RoundingMode.HALF_UP); navValue = navValue.setScale(4, RoundingMode.HALF_UP);
            // The temporary CAMS account is emptied above, so retain every transaction row
            // from this statement. Distinct transactions can share date, amount, NAV and units.
            // For Rajesh, keep the existing cross-upload duplicate protection.
            if (!owner.equals("cams") && purchases.existsByOwnerUsernameAndFundIdAndPurchaseDateAndAmountAndUnits(owner, f.id, tx.date(), amount, units)) { duplicates++; continue; }
            Purchase p = new Purchase(); p.fund = f; p.ownerUsername = owner; p.purchaseDate = tx.date();
            p.amount = amount; p.units = units; p.nav = navValue; p.broker = "CAS";
            p.remark = "Imported from CAS; please verify."; purchases.save(p); imported++;
        }
        if (owner.equals("cams")) funds.deleteUnlinkedFunds();

        // Link newly imported schemes immediately while the complete CAS context is available.
        // The AMFI refresh is best-effort: purchases remain imported even if AMFI is unavailable.
        NavService.RefreshResult refresh = nav.refresh();
        List<Fund> ownerFunds = funds.findAllWithPurchasesByOwner(owner);
        long unmappedFunds = ownerFunds.stream().filter(f -> f.amfiCode == null).count();
        return Map.of("imported", imported, "skipped", duplicates,
                "linkedFunds", ownerFunds.size() - unmappedFunds,
                "unmappedFunds", unmappedFunds,
                "navRefreshOk", refresh.ok(), "navRefreshMessage", refresh.message());
    }

    @GetMapping("/meta")
    public Map<String, List<String>> meta() {
        List<Fund> all = funds.findAll();
        return Map.of(
                "fundHouses", all.stream().map(f -> f.fundHouse).filter(Objects::nonNull).distinct().sorted().toList(),
                "categories", all.stream().map(f -> f.category).filter(Objects::nonNull).distinct().sorted().toList());
    }

    // ---------------------------------------------------------------- purchases
    @PostMapping("/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    public PortfolioService.PurchaseView addPurchase(@Valid @RequestBody PurchaseRequest r, HttpSession session) {
        Purchase p = new Purchase();
        p.fund = fund(r.fundId());
        p.ownerUsername = user(session);
        fill(p, r);
        p = purchases.save(p);
        return new PortfolioService.PurchaseView(p.id, p.purchaseDate, p.amount, p.units, p.nav, p.broker, p.remark, null, null, null);
    }

    @PutMapping("/purchases/{id}")
    public PortfolioService.PurchaseView updatePurchase(@PathVariable Long id, @Valid @RequestBody PurchaseRequest r, HttpSession session) {
        String owner = user(session);
        Purchase p = purchases.findById(id).filter(x -> x.ownerUsername.equals(owner)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        p.fund = fund(r.fundId());
        fill(p, r);
        p = purchases.save(p);
        return new PortfolioService.PurchaseView(p.id, p.purchaseDate, p.amount, p.units, p.nav, p.broker, p.remark, null, null, null);
    }

    @DeleteMapping("/purchases/{id}")
    public ResponseEntity<Void> deletePurchase(@PathVariable Long id, HttpSession session) {
        if (!purchases.existsByIdAndOwnerUsername(id, user(session))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found");
        purchases.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- helpers
    private void fill(Purchase p, PurchaseRequest r) {
        if (r.date().isAfter(LocalDate.now())) throw new IllegalArgumentException("Purchase date can't be in the future");
        if (r.units() == null && r.nav() == null) throw new IllegalArgumentException("Enter either the units allotted or the NAV");
        BigDecimal units = r.units() != null ? r.units() : r.amount().divide(r.nav(), 3, RoundingMode.HALF_UP);
        BigDecimal navV = r.nav() != null ? r.nav() : r.amount().divide(r.units(), 4, RoundingMode.HALF_UP);
        p.purchaseDate = r.date();
        p.amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        p.units = units.setScale(4, RoundingMode.HALF_UP);
        p.nav = navV.setScale(4, RoundingMode.HALF_UP);
        p.broker = r.broker() == null || r.broker().isBlank() ? null : r.broker().trim();
        p.remark = r.remark() == null || r.remark().isBlank() ? null : r.remark().trim();
    }

    private void copy(FundRequest r, Fund f) {
        f.schemeName = r.schemeName().trim();
        f.fundHouse = trim(r.fundHouse());
        f.category = trim(r.category());
        try { f.plan = r.plan() == null || r.plan().isBlank() ? Fund.Plan.UNKNOWN : Fund.Plan.valueOf(r.plan().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Plan must be DIRECT, REGULAR or UNKNOWN"); }
        f.amfiCode = trim(r.amfiCode());
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String normalize(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    private Fund fund(Long id) {
        return funds.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fund not found"));
    }

    private FundRow row(Fund f, String owner) {
        return new FundRow(f.id, f.schemeName, f.fundHouse, f.category, f.plan.name(), f.amfiCode, f.amfiSchemeName,
                f.mappingStatus.name(), f.latestNav, f.navDate, f.navSource, (int) purchases.countByFundIdAndOwnerUsername(f.id, owner));
    }

    private static String user(HttpSession session) { return (String) session.getAttribute("mftracker.user"); }

    // ---------------------------------------------------------------- errors
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String m = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage()).reduce((a, b) -> a + "; " + b).orElse("Invalid request");
        return ResponseEntity.badRequest().body(Map.of("error", m));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
    }
}
