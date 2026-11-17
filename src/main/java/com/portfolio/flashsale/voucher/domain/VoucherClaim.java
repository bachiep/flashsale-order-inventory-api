package com.portfolio.flashsale.voucher.domain;

import com.portfolio.flashsale.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "voucher_claims")
public class VoucherClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    private FlashVoucher voucher;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoucherClaimStatus status;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected VoucherClaim() {
    }

    public VoucherClaim(FlashVoucher voucher, User user, VoucherClaimStatus status, Instant claimedAt) {
        this.voucher = voucher;
        this.user = user;
        this.status = status;
        this.claimedAt = claimedAt;
    }

    public Long getId() {
        return id;
    }

    public FlashVoucher getVoucher() {
        return voucher;
    }

    public User getUser() {
        return user;
    }

    public VoucherClaimStatus getStatus() {
        return status;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
