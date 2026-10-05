package com.mftracker;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "purchase", indexes = @Index(name = "idx_purchase_fund", columnList = "fund_id"))
public class Purchase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "fund_id")
    public Fund fund;

    @Column(name = "owner_username", nullable = false, length = 40, columnDefinition = "varchar(40) not null default 'rajesh'")
    public String ownerUsername = "rajesh";

    @Column(nullable = false) public LocalDate purchaseDate;
    @Column(nullable = false, precision = 15, scale = 2) public BigDecimal amount;
    @Column(nullable = false, precision = 18, scale = 4) public BigDecimal units;
    @Column(nullable = false, precision = 14, scale = 4) public BigDecimal nav;
    @Column(length = 40) public String broker;
    @Column(length = 500) public String remark;
}
