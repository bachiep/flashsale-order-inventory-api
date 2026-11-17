package com.portfolio.flashsale.voucher.dto;

import com.portfolio.flashsale.voucher.domain.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record CreateVoucherRequest(
        @NotBlank(message = "Voucher code is required")
        @Size(max = 50, message = "Voucher code must not exceed 50 characters")
        String code,

        @NotNull(message = "Discount type is required")
        DiscountType discountType,

        @NotNull(message = "Discount value is required")
        @DecimalMin(value = "0.01", message = "Discount value must be greater than zero")
        BigDecimal discountValue,

        @DecimalMin(value = "0.00", message = "Minimum order amount must be non-negative")
        BigDecimal minOrderAmount,

        @NotNull(message = "Max redemptions is required")
        @Min(value = 1, message = "Max redemptions must be at least 1")
        Integer maxRedemptions,

        @NotNull(message = "Start timestamp is required")
        Instant startsAt,

        @NotNull(message = "End timestamp is required")
        Instant endsAt,

        Boolean active
) {
}
