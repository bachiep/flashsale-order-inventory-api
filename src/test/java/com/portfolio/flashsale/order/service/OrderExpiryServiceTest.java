package com.portfolio.flashsale.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.catalog.service.CatalogService;
import com.portfolio.flashsale.common.config.AppProperties;
import com.portfolio.flashsale.inventory.repository.InventoryItemRepository;
import com.portfolio.flashsale.order.domain.Order;
import com.portfolio.flashsale.order.domain.OrderItem;
import com.portfolio.flashsale.order.domain.OrderStatus;
import com.portfolio.flashsale.order.repository.OrderRepository;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class OrderExpiryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private VoucherClaimRepository voucherClaimRepository;

    @Mock
    private CatalogService catalogService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private OrderExpiryService expiryService;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-06-01T10:20:00Z"), ZoneOffset.UTC);
        AppProperties props = new AppProperties(
                new AppProperties.Jwt("test-secret-key-with-more-than-32-bytes-length", 900, 604800),
                new AppProperties.Order(900),
                new AppProperties.Scheduler(new AppProperties.Scheduler.ReservationExpiry(true, 30000, 50)),
                new AppProperties.Seed(false)
        );
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        expiryService = new OrderExpiryService(
                orderRepository,
                inventoryItemRepository,
                voucherClaimRepository,
                catalogService,
                props,
                clock,
                transactionManager
        );
    }

    @Test
    void skipsStockReleaseWhenAtomicOrderTransitionLosesConcurrentRace() {
        Instant createdAt = Instant.parse("2026-06-01T10:00:00Z");
        User buyer = new User("buyer@example.com", "pw", "Buyer", Role.ROLE_CUSTOMER, createdAt);
        ReflectionTestUtils.setField(buyer, "id", 10L);

        Product product = new Product("SKU-1", "Item", "Desc", "Tech", new BigDecimal("50.00"), true, 5, createdAt);
        ReflectionTestUtils.setField(product, "id", 20L);

        FlashVoucher voucher = new FlashVoucher(
                "V10",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                5,
                createdAt.minusSeconds(60),
                createdAt.plusSeconds(3600),
                true,
                createdAt
        );
        ReflectionTestUtils.setField(voucher, "id", 30L);

        Order order = new Order(
                "FS-RACE1",
                buyer,
                voucher,
                OrderStatus.PENDING_PAYMENT,
                new BigDecimal("50.00"),
                new BigDecimal("10.00"),
                new BigDecimal("40.00"),
                createdAt.plusSeconds(900),
                createdAt
        );
        ReflectionTestUtils.setField(order, "id", 100L);
        order.addItem(new OrderItem(product, "SKU-1", "Item", 1, new BigDecimal("50.00"), new BigDecimal("50.00")));

        when(orderRepository.findExpiredPendingOrderIds(eq(clock.instant()), any())).thenReturn(List.of(100L));
        when(orderRepository.findDetailedById(100L)).thenReturn(Optional.of(order));
        // Concurrent confirm/cancel already updated order row -> returns 0
        when(orderRepository.markExpiredIfPendingAndPastDeadline(100L, clock.instant())).thenReturn(0);

        int expiredCount = expiryService.expirePendingReservations();

        assertThat(expiredCount).isEqualTo(0);
        verify(inventoryItemRepository, never()).releaseReservedStock(any(), anyInt(), any());
        verify(voucherClaimRepository, never()).restoreToClaimed(any(), any());
        verify(catalogService, never()).evictAllCatalogCaches();
    }

    @Test
    void releasesStockAndRestoresVoucherWhenExpiryTransitionSucceeds() {
        Instant createdAt = Instant.parse("2026-06-01T10:00:00Z");
        User buyer = new User("buyer@example.com", "pw", "Buyer", Role.ROLE_CUSTOMER, createdAt);
        ReflectionTestUtils.setField(buyer, "id", 10L);

        Product product = new Product("SKU-1", "Item", "Desc", "Tech", new BigDecimal("50.00"), true, 5, createdAt);
        ReflectionTestUtils.setField(product, "id", 20L);

        FlashVoucher voucher = new FlashVoucher(
                "V10",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                5,
                createdAt.minusSeconds(60),
                createdAt.plusSeconds(3600),
                true,
                createdAt
        );
        ReflectionTestUtils.setField(voucher, "id", 30L);

        Order order = new Order(
                "FS-EXP1",
                buyer,
                voucher,
                OrderStatus.PENDING_PAYMENT,
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                new BigDecimal("90.00"),
                createdAt.plusSeconds(900),
                createdAt
        );
        ReflectionTestUtils.setField(order, "id", 101L);
        order.addItem(new OrderItem(product, "SKU-1", "Item", 2, new BigDecimal("50.00"), new BigDecimal("100.00")));

        when(orderRepository.findExpiredPendingOrderIds(eq(clock.instant()), any()))
                .thenReturn(List.of(101L));
        when(orderRepository.findDetailedById(101L)).thenReturn(Optional.of(order));
        when(orderRepository.markExpiredIfPendingAndPastDeadline(101L, clock.instant())).thenReturn(1);

        int expiredCount = expiryService.expirePendingReservations();

        assertThat(expiredCount).isEqualTo(1);
        verify(inventoryItemRepository).releaseReservedStock(20L, 2, clock.instant());
        verify(voucherClaimRepository).restoreToClaimed(30L, 10L);
        verify(catalogService).evictAllCatalogCaches();
    }
}
