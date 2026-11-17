package com.portfolio.flashsale.voucher.repository;

import com.portfolio.flashsale.voucher.domain.VoucherClaim;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherClaimRepository extends JpaRepository<VoucherClaim, Long> {

    boolean existsByVoucherIdAndUserId(Long voucherId, Long userId);

    @EntityGraph(attributePaths = "voucher")
    List<VoucherClaim> findAllWithVoucherByUserIdOrderByClaimedAtDesc(Long userId);

    @EntityGraph(attributePaths = "voucher")
    Optional<VoucherClaim> findWithVoucherByVoucherIdAndUserId(Long voucherId, Long userId);
}
