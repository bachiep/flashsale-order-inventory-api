package com.portfolio.flashsale.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.catalog.service.CatalogService;
import com.portfolio.flashsale.common.config.AppProperties;
import com.portfolio.flashsale.common.exception.ConflictException;
import com.portfolio.flashsale.common.exception.ForbiddenException;
import com.portfolio.flashsale.inventory.repository.InventoryItemRepository;
import com.portfolio.flashsale.order.domain.Order;
import com.portfolio.flashsale.order.domain.OrderStatus;
import com.portfolio.flashsale.order.dto.OrderResponse;
import com.portfolio.flashsale.order.repository.IdempotencyRecordRepository;
import com.portfolio.flashsale.order.repository.OrderRepository;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private FlashVoucherRepository voucherRepository;

    @Mock
    private VoucherClaimRepository voucherClaimRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CatalogService catalogService;

    private OrderService orderService;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);
        AppProperties props = new AppProperties(
                new AppProperties.Jwt("test-secret-key-with-more-than-32-bytes-length", 900, 604800),
                new AppProperties.Order(900),
                new AppProperties.Scheduler(new AppProperties.Scheduler.ReservationExpiry(false, 30000, 50)),
                new AppProperties.Seed(false)
        );
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        orderService = new OrderService(
                orderRepository,
                idempotencyRecordRepository,
                productRepository,
                inventoryItemRepository,
                voucherRepository,
                voucherClaimRepository,
                userRepository,
                catalogService,
                mapper,
                props,
                clock
        );
    }

    @Test
    void createsOrderAppliesClaimedVoucherAndSavesIdempotencySnapshot() {
        Instant now = clock.instant();
        Product product = new Product("SKU-1", "Keyboard", "Desc", "Tech", new BigDecimal("100.00"), true, 10, now);
        ReflectionTestUtils.setField(product, "id", 1L);

        FlashVoucher voucher = new FlashVoucher(
                "SAVE20",
                DiscountType.PERCENTAGE,
                new BigDecimal("20.00"),
                new BigDecimal("50.00"),
                5,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                true,
                now
        );
        ReflectionTestUtils.setField(voucher, "id", 9L);

        User user = new User("buyer@example.com", "pw", "Buyer", Role.ROLE_CUSTOMER, now);
        ReflectionTestUtils.setField(user, "id", 7L);

        when(productRepository.findAllById(any())).thenReturn(List.of(product));
        when(inventoryItemRepository.reserveStock(1L, 2, now)).thenReturn(1);
        when(voucherRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.of(voucher));
        when(voucherClaimRepository.markAppliedIfClaimed(9L, 7L, now)).thenReturn(1);
        when(userRepository.getReferenceById(7L)).thenReturn(user);
        when(voucherRepository.getReferenceById(9L)).thenReturn(voucher);
        when(productRepository.getReferenceById(1L)).thenReturn(product);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 101L);
            return order;
        });

        Map<Long, Integer> quantities = new TreeMap<>();
        quantities.put(1L, 2);

        OrderResponse response = orderService.createOrderTransactionally(7L, "idem-1", "hash-1", quantities, "SAVE20");

        assertThat(response.id()).isEqualTo(101L);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(response.subtotalAmount()).isEqualByComparingTo("200.00");
        assertThat(response.discountAmount()).isEqualByComparingTo("40.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("160.00");
        assertThat(response.expiresAt()).isEqualTo(now.plusSeconds(900));
        verify(idempotencyRecordRepository).saveAndFlush(any());
    }

    @Test
    void throwsConflictWhenStockReservationFails() {
        Instant now = clock.instant();
        Product product = new Product("SKU-LOW", "Mouse", "Desc", "Tech", new BigDecimal("40.00"), true, 1, now);
        ReflectionTestUtils.setField(product, "id", 2L);

        when(productRepository.findAllById(any())).thenReturn(List.of(product));
        when(inventoryItemRepository.reserveStock(2L, 3, now)).thenReturn(0);

        Map<Long, Integer> quantities = Map.of(2L, 3);

        assertThatThrownBy(() -> orderService.createOrderTransactionally(1L, "idem-2", "hash-2", quantities, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Insufficient available stock");
    }

    @Test
    void rejectsOrderAccessAndConfirmForNonOwnerNonAdmin() {
        Instant now = clock.instant();
        User owner = new User("owner@example.com", "pw", "Owner", Role.ROLE_CUSTOMER, now);
        ReflectionTestUtils.setField(owner, "id", 50L);

        Order order = new Order(
                "FS-ABC123",
                owner,
                null,
                OrderStatus.PENDING_PAYMENT,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                now.plusSeconds(600),
                now
        );
        ReflectionTestUtils.setField(order, "id", 200L);

        when(orderRepository.findDetailedById(200L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getOrder(99L, false, 200L))
                .isInstanceOf(ForbiddenException.class);

        when(orderRepository.findDetailedById(eq(200L))).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> orderService.confirmOrder(99L, false, 200L))
                .isInstanceOf(ForbiddenException.class);
    }
}
