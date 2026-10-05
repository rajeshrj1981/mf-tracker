package com.mftracker;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** One scheme/plan you hold (e.g. "Nippon Large Cap - Regular Growth"). */
@Entity
@Table(name = "fund")
public class Fund {
    public enum Plan { DIRECT, REGULAR, UNKNOWN }
    public enum MappingStatus { UNMAPPED, AUTO, CONFIRMED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String schemeName;
    public String fundHouse;
    public String category;

    @Enumerated(EnumType.STRING) @Column(length = 16)
    public Plan plan = Plan.UNKNOWN;

    /** AMFI scheme code - the key used to fetch live NAV. */
    @Column(length = 16)
    public String amfiCode;
    @Column(length = 400)
    public String amfiSchemeName;
    @Enumerated(EnumType.STRING) @Column(length = 16)
    public MappingStatus mappingStatus = MappingStatus.UNMAPPED;

    @Column(precision = 14, scale = 4) public BigDecimal latestNav;
    public LocalDate navDate;
    @Column(length = 16) public String navSource;      // AMFI | MFAPI | SPREADSHEET | MANUAL
    public LocalDateTime navUpdatedAt;
    @Column(precision = 14, scale = 4) public BigDecimal sheetNav;   // NAV from your spreadsheet (used to sanity-check matches)

    @OneToMany(mappedBy = "fund", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("purchaseDate ASC, id ASC")
    public List<Purchase> purchases = new ArrayList<>();
}
