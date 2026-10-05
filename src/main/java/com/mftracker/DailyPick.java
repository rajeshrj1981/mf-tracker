package com.mftracker;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** The suggestion made for one calendar day. Stored so it stays stable through the day and can be reviewed later. */
@Entity
@Table(name = "daily_pick", uniqueConstraints = @UniqueConstraint(columnNames = "pick_date"))
public class DailyPick {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "pick_date", nullable = false) public LocalDate pickDate;
    public Long fundId;                       // plain id (no FK) so deleting a fund never breaks history
    public String fundName;
    @Column(precision = 15, scale = 2) public BigDecimal amount;
    @Column(precision = 14, scale = 4) public BigDecimal navAtPick;
    public Double oneWeekPct;
    public Double oneMonthPct;
    @Column(length = 16) public String source;           // CLAUDE | RULE
    @Column(length = 1000) public String message;
    @Column(columnDefinition = "TEXT") public String rationale;
    @Column(columnDefinition = "MEDIUMTEXT") public String rankingJson;
    public LocalDateTime createdAt;
}
