package com.portfolio.flashsale.voucher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.common.exception.BadRequestException;
import com.portfolio.flashsale.common.exception.ConflictException;
import com.portfolio.flashsale.common.observability.FlashSaleMetrics;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.dto.CreateVoucherRequest;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class VoucherServiceTest {

    @Mock
    private FlashVoucherRepository voucherRepository;

    @Mock
    private VoucherClaimRepository claimRepository;

    @Mock
    private UserRepository userRepository;

    private VoucherService voucherService;
    private Clock clock;
    private FlashSaleMetrics flashSaleMetrics;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-01T10:00:00Z"), ZoneOffset.UTC);
        flashSaleMetrics = new FlashSaleMetrics(new SimpleMeterRegistry(), clock);
        voucherService = new VoucherService(voucherRepository, claimRepository, userRepository, clock, flashSaleMetrics);
    }

    @Test
    void rejectsVoucherCreationWithInvalidTimeWindowOrPercentageOver100() {
        Instant start = clock.instant();
        CreateVoucherRequest invalidWindow = new CreateVoucherRequest(
                "BADTIME",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                10,
                start,
                start,
                true
        );
        assertThatThrownBy(() -> voucherService.createVoucher(invalidWindow))
                .isInstanceOf(BadRequestException.class);

        CreateVoucherRequest invalidPercent = new CreateVoucherRequest(
                "BADPCT",
                DiscountType.PERCENTAGE,
                new BigDecimal("105.00"),
                BigDecimal.ZERO,
                10,
                start,
                start.plusSeconds(3600),
                true
        );
        assertThatThrownBy(() -> voucherService.createVoucher(invalidPercent))
                .isInstanceOf(BadRequestException.class);

        verify(voucherRepository, never()).saveAndFlush(any());
    }

    @Test
    void calculatesPercentageAndFixedDiscountsWithSubtotalCap() {
        Instant now = clock.instant();
        FlashVoucher pctVoucher = new FlashVoucher(
                "PCT20",
                DiscountType.PERCENTAGE,
                new BigDecimal("20.00"),
                new BigDecimal("50.00"),
                10,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                true,
                now
        );
        assertThat(pctVoucher.calculateDiscount(new BigDecimal("125.00"))).isEqualByComparingTo("25.00");

        FlashVoucher fixedVoucher = new FlashVoucher(
                "FLAT50",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("50.00"),
                new BigDecimal("10.00"),
                10,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                true,
                now
        );
        assertThat(fixedVoucher.calculateDiscount(new BigDecimal("35.00"))).isEqualByComparingTo("35.00");
    }

    @Test
    void rejectsClaimWhenVoucherOutsideTimeWindowOrAlreadyClaimed() {
        Instant now = clock.instant();
        FlashVoucher expired = new FlashVoucher(
                "OLD10",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                5,
                now.minusSeconds(7200),
                now.minusSeconds(60),
                true,
                now.minusSeconds(7200)
        );
        ReflectionTestUtils.setField(expired, "id", 1L);
        when(voucherRepository.findByCodeIgnoreCase("OLD10")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> voucherService.claimVoucher(10L, "old10"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not active");

        FlashVoucher active = new FlashVoucher(
                "LIVE10",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                5,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                true,
                now
        );
        ReflectionTestUtils.setField(active, "id", 2L);
        when(voucherRepository.findByCodeIgnoreCase("LIVE10")).thenReturn(Optional.of(active));
        when(claimRepository.existsByVoucherIdAndUserId(2L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> voucherService.claimVoucher(10L, "LIVE10"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already claimed");
    }
}
