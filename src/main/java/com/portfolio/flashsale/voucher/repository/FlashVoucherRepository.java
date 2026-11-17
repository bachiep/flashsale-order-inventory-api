package com.portfolio.flashsale.voucher.repository;

import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashVoucherRepository extends JpaRepository<FlashVoucher, Long> {

    Optional<FlashVoucher> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    @Query("""
            SELECT v FROM FlashVoucher v
            WHERE v.active = true
              AND v.startsAt <= :now
              AND v.endsAt >= :now
            ORDER BY v.endsAt ASC
            """)
    List<FlashVoucher> findActiveCampaigns(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE FlashVoucher v
            SET v.claimedCount = v.claimedCount + 1,
                v.version = v.version + 1
            WHERE v.id = :voucherId
              AND v.active = true
              AND v.claimedCount < v.maxRedemptions
              AND v.startsAt <= :now
              AND v.endsAt >= :now
            """)
    int incrementClaimedCountIfAvailable(@Param("voucherId") Long voucherId, @Param("now") Instant now);
}
