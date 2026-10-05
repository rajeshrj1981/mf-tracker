package com.mftracker;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PortfolioService {
    public record PurchaseView(Long id, LocalDate date, BigDecimal amount, BigDecimal units, BigDecimal nav, String broker,
                               String remark, BigDecimal currentValue, BigDecimal gain, BigDecimal gainPct) { }
    public record FundView(Long id, String schemeName, String fundHouse, String category, String plan, String amfiCode,
                           String mappingStatus, BigDecimal units, BigDecimal invested, BigDecimal avgNav, BigDecimal latestNav,
                           LocalDate navDate, String navSource, boolean navStale, BigDecimal currentValue, BigDecimal gain,
                           BigDecimal gainPct, List<PurchaseView> purchases) { }
    public record Totals(BigDecimal invested, BigDecimal currentValue, BigDecimal gain, BigDecimal gainPct, int funds, int purchases) { }
    public record Group(String name, Totals totals, List<FundView> funds) { }
    public record Portfolio(String groupBy, Totals totals, List<Group> groups, LocalDateTime navRefreshedAt, int unmappedFunds, int staleFunds) { }
    public record StrategyFund(Long fundId, String schemeName, String plan, BigDecimal invested, BigDecimal gain) { }
    public record StrategySummary(LocalDate from, LocalDate to, List<StrategyFund> funds) { }

    private final FundRepository funds;
    private final NavService nav;

    public PortfolioService(FundRepository funds, NavService nav) { this.funds = funds; this.nav = nav; }

    @Transactional(readOnly = true)
    public Portfolio build(String groupBy, String owner) {
        List<FundView> views = funds.findAllWithPurchasesByOwner(owner).stream().map(f -> view(f, owner)).toList();
        Map<String, List<FundView>> grouped = new LinkedHashMap<>();
        for (FundView v : views) {
            String key = switch (groupBy == null ? "" : groupBy) {
                case "fundHouse" -> blank(v.fundHouse(), "Unassigned");
                case "category" -> blank(v.category(), "Uncategorised");
                default -> "All funds";
            };
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
        List<Group> groups = grouped.entrySet().stream().map(e -> {
            List<FundView> fs = e.getValue().stream().sorted(Comparator.comparing(FundView::currentValue).reversed()).toList();
            return new Group(e.getKey(), totals(fs), fs);
        }).sorted(Comparator.comparing((Group g) -> g.totals().currentValue()).reversed()).toList();
        int unmapped = (int) views.stream().filter(v -> v.amfiCode() == null).count();
        int stale = (int) views.stream().filter(FundView::navStale).count();
        return new Portfolio(groupBy == null ? "none" : groupBy, totals(views), groups, nav.lastRefresh(), unmapped, stale);
    }

    @Transactional(readOnly = true)
    public StrategySummary strategySummary(LocalDate from, LocalDate to, String owner) {
        if (from == null || to == null || to.isBefore(from))
            throw new IllegalArgumentException("The strategy period must have a valid start and end date.");
        List<StrategyFund> result = funds.findAllWithPurchasesByOwner(owner).stream().map(f -> {
            List<Purchase> period = f.purchases.stream().filter(p -> p.ownerUsername.equals(owner)).filter(p -> {
                DayOfWeek day = p.purchaseDate.getDayOfWeek();
                return !p.purchaseDate.isBefore(from) && !p.purchaseDate.isAfter(to)
                        && day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
            }).toList();
            if (period.isEmpty()) return null;
            BigDecimal invested = period.stream().map(p -> p.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gain = null;
            if (f.latestNav != null) {
                BigDecimal currentValue = period.stream().map(p -> p.units.multiply(f.latestNav))
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
                gain = currentValue.subtract(invested).setScale(2, RoundingMode.HALF_UP);
            }
            return new StrategyFund(f.id, f.schemeName, f.plan.name(), invested, gain);
        }).filter(Objects::nonNull).sorted(Comparator.comparing(StrategyFund::invested).reversed()).toList();
        return new StrategySummary(from, to, result);
    }

    private FundView view(Fund f, String owner) {
        BigDecimal navNow = f.latestNav == null ? BigDecimal.ZERO : f.latestNav;
        BigDecimal units = BigDecimal.ZERO, invested = BigDecimal.ZERO;
        List<PurchaseView> pvs = new ArrayList<>();
        for (Purchase p : f.purchases) {
            if (!p.ownerUsername.equals(owner)) continue;
            units = units.add(p.units); invested = invested.add(p.amount);
            BigDecimal cv = p.units.multiply(navNow).setScale(2, RoundingMode.HALF_UP);
            BigDecimal g = cv.subtract(p.amount);
            pvs.add(new PurchaseView(p.id, p.purchaseDate, p.amount, p.units, p.nav, p.broker, p.remark, cv, g, pct(g, p.amount)));
        }
        BigDecimal cv = units.multiply(navNow).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gain = cv.subtract(invested);
        BigDecimal avg = units.signum() == 0 ? null : invested.divide(units, 4, RoundingMode.HALF_UP);
        boolean stale = "SPREADSHEET".equals(f.navSource) || f.navDate == null
                || f.navDate.isBefore(LocalDate.now().minusDays(5));
        return new FundView(f.id, f.schemeName, f.fundHouse, f.category, f.plan.name(), f.amfiCode, f.mappingStatus.name(),
                units, invested, avg, f.latestNav, f.navDate, f.navSource, stale, cv, gain, pct(gain, invested), pvs);
    }

    private Totals totals(List<FundView> fs) {
        BigDecimal inv = fs.stream().map(FundView::invested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cv = fs.stream().map(FundView::currentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gain = cv.subtract(inv);
        int pc = fs.stream().mapToInt(f -> f.purchases().size()).sum();
        return new Totals(inv, cv, gain, pct(gain, inv), fs.size(), pc);
    }

    private static BigDecimal pct(BigDecimal gain, BigDecimal base) {
        return base.signum() == 0 ? null : gain.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }
    private static String blank(String s, String d) { return s == null || s.isBlank() ? d : s; }
}
