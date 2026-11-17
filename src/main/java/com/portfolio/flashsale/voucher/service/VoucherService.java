package com.portfolio.flashsale.voucher.service;

import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.common.exception.BadRequestException;
import com.portfolio.flashsale.common.exception.ConflictException;
import com.portfolio.flashsale.common.exception.ResourceNotFoundException;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.domain.VoucherClaim;
import com.portfolio.flashsale.voucher.domain.VoucherClaimStatus;
import com.portfolio.flashsale.voucher.dto.CreateVoucherRequest;
import com.portfolio.flashsale.voucher.dto.VoucherClaimResponse;
import com.portfolio.flashsale.voucher.dto.VoucherResponse;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherService {

    private static final BigDecimal MAX_PERCENTAGE = BigDecimal.valueOf(100);

    private final FlashVoucherRepository voucherRepository;
    private final VoucherClaimRepository claimRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public VoucherService(
            FlashVoucherRepository voucherRepository,
            VoucherClaimRepository claimRepository,
            UserRepository userRepository,
            Clock clock
    ) {
        this.voucherRepository = voucherRepository;
        this.claimRepository = claimRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public VoucherResponse createVoucher(CreateVoucherRequest request) {
        String normalizedCode = normalizeCode(request.code());
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new BadRequestException("INVALID_VOUCHER_WINDOW", "endsAt must be strictly after startsAt");
        }
        if (request.discountType() == DiscountType.PERCENTAGE && request.discountValue().compareTo(MAX_PERCENTAGE) > 0) {
            throw new BadRequestException("INVALID_DISCOUNT_VALUE", "Percentage discount cannot exceed 100");
        }
        if (voucherRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new ConflictException("DUPLICATE_VOUCHER_CODE", "Voucher code already exists: " + normalizedCode);
        }

        BigDecimal minOrderAmount = request.minOrderAmount() == null ? BigDecimal.ZERO : request.minOrderAmount();
        boolean active = request.active() == null || request.active();

        FlashVoucher voucher = new FlashVoucher(
                normalizedCode,
                request.discountType(),
                request.discountValue(),
                minOrderAmount,
                request.maxRedemptions(),
                request.startsAt(),
                request.endsAt(),
                active,
                clock.instant()
        );
        return VoucherResponse.from(voucherRepository.saveAndFlush(voucher));
    }

    @Transactional(readOnly = true)
    public List<VoucherResponse> listAllVouchers() {
        return voucherRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(VoucherResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VoucherResponse> listActiveVouchers() {
        return voucherRepository.findActiveCampaigns(clock.instant())
                .stream()
                .map(VoucherResponse::from)
                .toList();
    }

    @Transactional
    public VoucherClaimResponse claimVoucher(Long userId, String rawCode) {
        String code = normalizeCode(rawCode);
        FlashVoucher voucher = voucherRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new ResourceNotFoundException("VOUCHER_NOT_FOUND", "Voucher not found: " + code));

        Instant now = clock.instant();
        if (!voucher.isWithinActiveWindow(now)) {
            throw new ConflictException("VOUCHER_NOT_ACTIVE", "Voucher is not active in the current time window");
        }
        if (claimRepository.existsByVoucherIdAndUserId(voucher.getId(), userId)) {
            throw new ConflictException("VOUCHER_ALREADY_CLAIMED", "You have already claimed voucher " + code);
        }

        int updatedRows = voucherRepository.incrementClaimedCountIfAvailable(voucher.getId(), now);
        if (updatedRows == 0) {
            throw new ConflictException("VOUCHER_QUOTA_EXHAUSTED", "Voucher quota has been exhausted for " + code);
        }

        FlashVoucher voucherRef = voucherRepository.getReferenceById(voucher.getId());
        User userRef = userRepository.getReferenceById(userId);
        VoucherClaim savedClaim = claimRepository.saveAndFlush(
                new VoucherClaim(voucherRef, userRef, VoucherClaimStatus.CLAIMED, now)
        );
        return VoucherClaimResponse.of(savedClaim, voucher);
    }

    @Transactional(readOnly = true)
    public List<VoucherClaimResponse> listMyClaims(Long userId) {
        return claimRepository.findAllWithVoucherByUserIdOrderByClaimedAtDesc(userId)
                .stream()
                .map(claim -> VoucherClaimResponse.of(claim, claim.getVoucher()))
                .toList();
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
