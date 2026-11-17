package com.portfolio.flashsale.voucher.dto;

import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.domain.VoucherClaim;
import com.portfolio.flashsale.voucher.domain.VoucherClaimStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record VoucherClaimResponse(
        Long id,
        Long voucherId,
        String voucherCode,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        VoucherClaimStatus status,
        Instant claimedAt,
        Instant usedAt,
        Instant expiresAt
) {
    public static VoucherClaimResponse of(VoucherClaim claim, FlashVoucher voucher) {
        return new VoucherClaimResponse(
                claim.getId(),
                voucher.getId(),
                voucher.getCode(),
                voucher.getDiscountType(),
                voucher.getDiscountValue(),
                voucher.getMinOrderAmount(),
                claim.getStatus(),
                claim.getClaimedAt(),
                claim.getUsedAt(),
                voucher.getEndsAt()
        );
    }
}
