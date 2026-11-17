package com.portfolio.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.repository.RefreshTokenRepository;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.auth.security.JwtService;
import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.inventory.domain.InventoryItem;
import com.portfolio.flashsale.inventory.repository.InventoryItemRepository;
import com.portfolio.flashsale.order.domain.Order;
import com.portfolio.flashsale.order.domain.OrderStatus;
import com.portfolio.flashsale.order.repository.IdempotencyRecordRepository;
import com.portfolio.flashsale.order.repository.OrderRepository;
import com.portfolio.flashsale.order.service.OrderExpiryService;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.domain.VoucherClaim;
import com.portfolio.flashsale.voucher.domain.VoucherClaimStatus;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderExpiryIntegrationTest.MutableClockConfig.class)
class OrderExpiryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock mutableClock;

    @Autowired
    private OrderExpiryService orderExpiryService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VoucherClaimRepository voucherClaimRepository;

    @Autowired
    private FlashVoucherRepository voucherRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        cleanAll();
        mutableClock.setInstant(Instant.parse("2026-06-01T10:00:00Z"));
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    @Test
    void expiresPendingOrderPastTtlWithoutSleepAndRestoresInventoryAndVoucher() throws Exception {
        Instant t0 = mutableClock.instant();
        User buyer = userRepository.save(new User(
                "ttl-buyer@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "TTL Buyer",
                Role.ROLE_CUSTOMER,
                t0
        ));
        String tokenAtT0 = jwtService.generateAccessToken(buyer);

        Product product = productRepository.save(new Product(
                "FS-TTL-01",
                "Mechanical Keyboard Pro",
                "Wireless",
                "Peripherals",
                new BigDecimal("150.00"),
                true,
                5,
                t0
        ));
        FlashVoucher voucher = voucherRepository.save(new FlashVoucher(
                "TTL30",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("30.00"),
                new BigDecimal("50.00"),
                5,
                t0.minusSeconds(60),
                t0.plusSeconds(7200),
                true,
                t0
        ));

        mockMvc.perform(post("/api/v1/vouchers/TTL30/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAtT0))
                .andExpect(status().isCreated());

        MvcResult orderResult = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAtT0)
                        .header("Idempotency-Key", "ttl-order-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{ "productId": %d, "quantity": 2 }],
                                  "voucherCode": "TTL30"
                                }
                                """.formatted(product.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn();

        Long orderId = objectMapper.readTree(orderResult.getResponse().getContentAsString()).get("id").asLong();

        // Advance clock to 14m59s: reservation is still valid
        mutableClock.setInstant(t0.plusSeconds(899));
        assertThat(orderExpiryService.expirePendingReservations()).isEqualTo(0);

        // Advance clock past 15m TTL: confirm must fail with 409, and scheduler expires + releases stock
        mutableClock.setInstant(t0.plusSeconds(901));
        String tokenAfterTtl = jwtService.generateAccessToken(buyer);

        mockMvc.perform(post("/api/v1/orders/{id}/confirm", orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAfterTtl))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_RESERVATION_EXPIRED"));

        int expiredCount = orderExpiryService.expirePendingReservations();
        assertThat(expiredCount).isEqualTo(1);

        Order expiredOrder = orderRepository.findDetailedById(orderId).orElseThrow();
        assertThat(expiredOrder.getStatus()).isEqualTo(OrderStatus.EXPIRED);

        InventoryItem restoredStock = inventoryItemRepository.findByProductId(product.getId()).orElseThrow();
        assertThat(restoredStock.getAvailableQty()).isEqualTo(5);
        assertThat(restoredStock.getReservedQty()).isEqualTo(0);
        assertThat(restoredStock.getSellableQty()).isEqualTo(5);

        VoucherClaim restoredClaim = voucherClaimRepository
                .findWithVoucherByVoucherIdAndUserId(voucher.getId(), buyer.getId())
                .orElseThrow();
        assertThat(restoredClaim.getStatus()).isEqualTo(VoucherClaimStatus.CLAIMED);

        // Second sweep is a no-op; confirming an EXPIRED order returns 409
        assertThat(orderExpiryService.expirePendingReservations()).isEqualTo(0);
        mockMvc.perform(post("/api/v1/orders/{id}/confirm", orderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAfterTtl))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ORDER_STATE"));
    }

    @Test
    void handlesMalformedJsonMissingProductAndExpiredVoucherBoundaryCases() throws Exception {
        Instant t0 = mutableClock.instant();
        User buyer = userRepository.save(new User(
                "edge-buyer@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Edge Buyer",
                Role.ROLE_CUSTOMER,
                t0
        ));
        String token = jwtService.generateAccessToken(buyer);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "bad-json-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_JSON"));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "missing-prod-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{ "productId": 999999, "quantity": 1 }]
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/products/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    private void cleanAll() {
        idempotencyRecordRepository.deleteAll();
        orderRepository.deleteAll();
        voucherClaimRepository.deleteAll();
        voucherRepository.deleteAll();
        productRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void setInstant(Instant instant) {
            this.current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
