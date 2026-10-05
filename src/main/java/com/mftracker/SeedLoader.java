package com.mftracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** On first start (empty DB) imports the holdings converted from your Excel file. */
@Component
public class SeedLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    record SeedPurchase(LocalDate date, BigDecimal amount, BigDecimal units, BigDecimal nav, String broker, String remark) { }
    record SeedFund(String schemeName, String fundHouse, String category, String plan, BigDecimal sheetNav,
                    LocalDate sheetNavDate, List<SeedPurchase> purchases) { }
    record SeedFile(List<SeedFund> funds) { }

    private final FundRepository funds;
    private final ObjectMapper mapper;
    @Value("${mftracker.seed-on-empty:true}") private boolean enabled;

    public SeedLoader(FundRepository funds, ObjectMapper mapper) { this.funds = funds; this.mapper = mapper; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled || funds.count() > 0) return;
        try (InputStream in = new ClassPathResource("seed/holdings.json").getInputStream()) {
            SeedFile file = mapper.readValue(in, SeedFile.class);
            int purchases = 0;
            for (SeedFund sf : file.funds()) {
                Fund f = new Fund();
                f.schemeName = sf.schemeName(); f.fundHouse = sf.fundHouse(); f.category = sf.category();
                f.plan = Fund.Plan.valueOf(sf.plan());
                f.sheetNav = sf.sheetNav(); f.latestNav = sf.sheetNav(); f.navDate = sf.sheetNavDate();
                f.navSource = "SPREADSHEET"; f.navUpdatedAt = LocalDateTime.now();
                for (SeedPurchase sp : sf.purchases()) {
                    Purchase p = new Purchase();
                    p.fund = f; p.purchaseDate = sp.date();
                    p.amount = sp.amount().setScale(2, RoundingMode.HALF_UP);
                    p.units = sp.units().setScale(4, RoundingMode.HALF_UP);
                    p.nav = sp.nav().setScale(4, RoundingMode.HALF_UP);
                    p.broker = sp.broker(); p.remark = sp.remark();
                    f.purchases.add(p); purchases++;
                }
                funds.save(f);
            }
            log.info("Seeded {} funds and {} purchases from seed/holdings.json", file.funds().size(), purchases);
        }
    }
}
