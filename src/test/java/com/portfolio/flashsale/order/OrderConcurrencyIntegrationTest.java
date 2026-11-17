package com.portfolio.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.portfolio.flashsale.order.repository.IdempotencyRecordRepository;
import com.portfolio.flashsale.order.repository.OrderRepository;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Autowired
    private Clock clock;

    @BeforeEach
    @AfterEach
    void cleanAll() {
        idempotencyRecordRepository.deleteAll();
        orderRepository.deleteAll();
        voucherClaimRepository.deleteAll();
        voucherRepository.deleteAll();
        productRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void enforcesIdempotencyKeyReplayAndPayloadConflict() throws Exception {
        Instant now = clock.instant();
        User buyer = userRepository.save(new User(
                "idem-buyer@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Idem Buyer",
                Role.ROLE_CUSTOMER,
                now
        ));
        String buyerToken = jwtService.generateAccessToken(buyer);

        Product product = productRepository.save(new Product(
                "FS-IDEM-01",
                "Flash SSD 1TB",
                "NVMe Gen4",
                "Storage",
                new BigDecimal("120.00"),
                true,
                10,
                now
        ));

        String payload = """
                {
                  "items": [
                    { "productId": %d, "quantity": 2 }
                  ]
                }
                """.formatted(product.getId());

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));

        MvcResult firstResult = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .header("Idempotency-Key", "checkout-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(240.00))
                .andReturn();

        String firstOrderNumber = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                .get("orderNumber").asText();

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .header("Idempotency-Key", "checkout-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.orderNumber").value(firstOrderNumber));

        InventoryItem inventory = inventoryItemRepository.findByProductId(product.getId()).orElseThrow();
        assertThat(inventory.getReservedQty()).isEqualTo(2);
        assertThat(orderRepository.count()).isEqualTo(1);

        // Same Idempotency-Key with different payload must return 409 Conflict
        String differentPayload = """
                {
                  "items": [
                    { "productId": %d, "quantity": 3 }
                  ]
                }
                """.formatted(product.getId());

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .header("Idempotency-Key", "checkout-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(differentPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void voucherOrderCancelRestoresStockAndVoucherClaimAndConfirmDeductsStock() throws Exception {
        Instant now = clock.instant();
        User buyer = userRepository.save(new User(
                "lifecycle-buyer@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Lifecycle Buyer",
                Role.ROLE_CUSTOMER,
                now
        ));
        User otherUser = userRepository.save(new User(
                "other-buyer@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Other Buyer",
                Role.ROLE_CUSTOMER,
                now
        ));
        String buyerToken = jwtService.generateAccessToken(buyer);
        String otherToken = jwtService.generateAccessToken(otherUser);

        Product product = productRepository.save(new Product(
                "FS-LIFE-01",
                "4K Monitor",
                "IPS 144Hz",
                "Monitors",
                new BigDecimal("300.00"),
                true,
                5,
                now
        ));
        voucherRepository.save(new FlashVoucher(
                "MON50",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("50.00"),
                new BigDecimal("100.00"),
                10,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                true,
                now
        ));

        mockMvc.perform(post("/api/v1/vouchers/MON50/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isCreated());

        String orderPayload = """
                {
                  "items": [{ "productId": %d, "quantity": 2 }],
                  "voucherCode": "MON50"
                }
                """.formatted(product.getId());

        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .header("Idempotency-Key", "life-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotalAmount").value(600.00))
                .andExpect(jsonPath("$.discountAmount").value(50.00))
                .andExpect(jsonPath("$.totalAmount").value(550.00))
                .andReturn();

        Long firstOrderId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // Other customer cannot view someone else's order
        mockMvc.perform(get("/api/v1/orders/{id}", firstOrderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        // Cancel releases reserved stock and restores claimed voucher
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", firstOrderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        InventoryItem afterCancel = inventoryItemRepository.findByProductId(product.getId()).orElseThrow();
        assertThat(afterCancel.getAvailableQty()).isEqualTo(5);
        assertThat(afterCancel.getReservedQty()).isEqualTo(0);

        // Reuse restored voucher on new order and confirm it
        MvcResult secondCreated = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .header("Idempotency-Key", "life-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload))
                .andExpect(status().isCreated())
                .andReturn();

        Long secondOrderId = objectMapper.readTree(secondCreated.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/orders/{id}/confirm", secondOrderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        InventoryItem afterConfirm = inventoryItemRepository.findByProductId(product.getId()).orElseThrow();
        assertThat(afterConfirm.getAvailableQty()).isEqualTo(3);
        assertThat(afterConfirm.getReservedQty()).isEqualTo(0);

        // Confirmed order cannot be cancelled
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", secondOrderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ORDER_STATE"));

        mockMvc.perform(get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void concurrent50ThreadsBuying10ItemsCreateExactly10OrdersWithZeroOversells() throws Exception {
        Instant now = clock.instant();
        Product flashProduct = productRepository.save(new Product(
                "FS-HOT-10",
                "Flash Console Limited",
                "Limited drop",
                "Gaming",
                new BigDecimal("499.00"),
                true,
                10,
                now
        ));

        int threadCount = 50;
        List<String> tokens = new ArrayList<>(threadCount);
        String encodedPw = passwordEncoder.encode("Pass@1234");
        for (int i = 0; i < threadCount; i++) {
            User buyer = userRepository.save(new User(
                    "order-racer-" + i + "@flashsale.local",
                    encodedPw,
                    "Buyer " + i,
                    Role.ROLE_CUSTOMER,
                    now
            ));
            tokens.add(jwtService.generateAccessToken(buyer));
        }

        String requestBody = """
                {
                  "items": [
                    { "productId": %d, "quantity": 1 }
                  ]
                }
                """.formatted(flashProduct.getId());

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                final String token = tokens.get(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    MvcResult result = mockMvc.perform(post("/api/v1/orders")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                    .header("Idempotency-Key", "race-key-" + index)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                            .andReturn();
                    return result.getResponse().getStatus();
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int createdCount = 0;
            int conflictCount = 0;
            for (Future<Integer> future : futures) {
                int code = future.get(20, TimeUnit.SECONDS);
                if (code == 201) {
                    createdCount++;
                } else if (code == 409) {
                    conflictCount++;
                } else {
                    throw new AssertionError("Unexpected HTTP status during 50-thread order race: " + code);
                }
            }

            assertThat(createdCount).isEqualTo(10);
            assertThat(conflictCount).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }

        InventoryItem finalInventory = inventoryItemRepository.findByProductId(flashProduct.getId()).orElseThrow();
        assertThat(finalInventory.getAvailableQty()).isEqualTo(10);
        assertThat(finalInventory.getReservedQty()).isEqualTo(10);
        assertThat(finalInventory.getSellableQty()).isEqualTo(0);
        assertThat(orderRepository.count()).isEqualTo(10);
    }
}
