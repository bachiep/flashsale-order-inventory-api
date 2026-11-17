package com.portfolio.flashsale.voucher.dto;

import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import java.math.BigDecimal;
import java.time.Instant;

public record VoucherResponse(
        Long id,
        String code,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        int maxRedemptions,
        int claimedCount,
        int remainingQuota,
        Instant startsAt,
        Instant endsAt,
        boolean active,
        Instant createdAt
) {
    public static VoucherResponse from(FlashVoucher voucher) {
        return new VoucherResponse(
                voucher.getId(),
                voucher.getCode(),
                voucher.getDiscountType(),
                voucher.getDiscountValue(),
                voucher.getMinOrderAmount(),
                voucher.getMaxRedemptions(),
                voucher.getClaimedCount(),
                voucher.getRemainingQuota(),
                voucher.getStartsAt(),
                voucher.getEndsAt(),
                voucher.isActive(),
                voucher.getCreatedAt()
        );
    }
}
