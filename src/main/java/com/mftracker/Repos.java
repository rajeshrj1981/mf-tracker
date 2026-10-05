package com.mftracker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface FundRepository extends JpaRepository<Fund, Long> {
    @Query("select distinct f from Fund f left join fetch f.purchases")
    List<Fund> findAllWithPurchases();

    @Query("select distinct f from Fund f join fetch f.purchases p where p.ownerUsername = :owner")
    List<Fund> findAllWithPurchasesByOwner(String owner);

    @Modifying
    @Query("delete from Fund f where not exists (select p.id from Purchase p where p.fund = f)")
    int deleteUnlinkedFunds();
}

interface PurchaseRepository extends JpaRepository<Purchase, Long> {
    boolean existsByIdAndOwnerUsername(Long id, String owner);
    long countByFundIdAndOwnerUsername(Long fundId, String owner);
    long deleteByOwnerUsername(String owner);
    boolean existsByOwnerUsernameAndFundIdAndPurchaseDateAndAmountAndUnits(String owner, Long fundId, LocalDate date, java.math.BigDecimal amount, java.math.BigDecimal units);
}

interface DailyPickRepository extends JpaRepository<DailyPick, Long> {
    Optional<DailyPick> findByPickDate(LocalDate date);
    List<DailyPick> findTop50ByOrderByPickDateDesc();
}
