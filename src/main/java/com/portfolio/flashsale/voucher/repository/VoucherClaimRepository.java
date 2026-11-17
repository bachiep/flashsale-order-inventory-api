package com.portfolio.flashsale.voucher.repository;

import com.portfolio.flashsale.voucher.domain.VoucherClaim;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherClaimRepository extends JpaRepository<VoucherClaim, Long> {

    boolean existsByVoucherIdAndUserId(Long voucherId, Long userId);

    @EntityGraph(attributePaths = "voucher")
    List<VoucherClaim> findAllWithVoucherByUserIdOrderByClaimedAtDesc(Long userId);

    @EntityGraph(attributePaths = "voucher")
    Optional<VoucherClaim> findWithVoucherByVoucherIdAndUserId(Long voucherId, Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE VoucherClaim vc
            SET vc.status = com.portfolio.flashsale.voucher.domain.VoucherClaimStatus.APPLIED,
                vc.usedAt = :now
            WHERE vc.voucher.id = :voucherId
              AND vc.user.id = :userId
              AND vc.status = com.portfolio.flashsale.voucher.domain.VoucherClaimStatus.CLAIMED
            """)
    int markAppliedIfClaimed(
            @Param("voucherId") Long voucherId,
            @Param("userId") Long userId,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE VoucherClaim vc
            SET vc.status = com.portfolio.flashsale.voucher.domain.VoucherClaimStatus.CLAIMED,
                vc.usedAt = null
            WHERE vc.voucher.id = :voucherId
              AND vc.user.id = :userId
              AND vc.status = com.portfolio.flashsale.voucher.domain.VoucherClaimStatus.APPLIED
            """)
    int restoreToClaimed(@Param("voucherId") Long voucherId, @Param("userId") Long userId);
}
