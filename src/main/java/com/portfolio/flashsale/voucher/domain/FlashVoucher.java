package com.portfolio.flashsale.voucher.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Entity
@Table(name = "flash_vouchers")
public class FlashVoucher {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "min_order_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal minOrderAmount;

    @Column(name = "max_redemptions", nullable = false)
    private int maxRedemptions;

    @Column(name = "claimed_count", nullable = false)
    private int claimedCount;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FlashVoucher() {
    }

    public FlashVoucher(
            String code,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            int maxRedemptions,
            Instant startsAt,
            Instant endsAt,
            boolean active,
            Instant createdAt
    ) {
        this.code = code;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minOrderAmount = minOrderAmount;
        this.maxRedemptions = maxRedemptions;
        this.claimedCount = 0;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.active = active;
        this.createdAt = createdAt;
    }

    public boolean isWithinActiveWindow(Instant now) {
        return active && !now.isBefore(startsAt) && !now.isAfter(endsAt);
    }

    public BigDecimal calculateDiscount(BigDecimal subtotal) {
        if (subtotal.compareTo(minOrderAmount) < 0) {
            throw new IllegalArgumentException("Subtotal below minimum order amount for voucher " + code);
        }
        BigDecimal rawDiscount = switch (discountType) {
            case PERCENTAGE -> subtotal.multiply(discountValue)
                    .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
            case FIXED_AMOUNT -> discountValue;
        };
        return rawDiscount.min(subtotal).setScale(2, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public BigDecimal getMinOrderAmount() {
        return minOrderAmount;
    }

    public int getMaxRedemptions() {
        return maxRedemptions;
    }

    public int getClaimedCount() {
        return claimedCount;
    }

    public int getRemainingQuota() {
        return Math.max(0, maxRedemptions - claimedCount);
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
