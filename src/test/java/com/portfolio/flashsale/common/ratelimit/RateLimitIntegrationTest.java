package com.portfolio.flashsale.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import com.portfolio.flashsale.common.observability.CorrelationIdFilter;
import com.portfolio.flashsale.common.observability.MetricsSummaryResponse;
import com.portfolio.flashsale.order.repository.IdempotencyRecordRepository;
import com.portfolio.flashsale.order.repository.OrderRepository;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FlashSaleRateLimitFilter rateLimitFilter;

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
        rateLimitFilter.clearBuckets();
        idempotencyRecordRepository.deleteAll();
        orderRepository.deleteAll();
        voucherClaimRepository.deleteAll();
        voucherRepository.deleteAll();
        productRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void voucherClaimBurstReturns429WithRetryAfterProblemDetailAndIncrementsMetric() throws Exception {
        MetricsSummaryResponse before = readMetricsSummary();
        Instant now = clock.instant();

        User actor = userRepository.save(new User(
                "rl-voucher-actor@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Rate Limit Actor",
                Role.ROLE_CUSTOMER,
                now
        ));
        User otherActor = userRepository.save(new User(
                "rl-voucher-other@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Other Actor",
                Role.ROLE_CUSTOMER,
                now
        ));
        String actorToken = jwtService.generateAccessToken(actor);
        String otherToken = jwtService.generateAccessToken(otherActor);

        voucherRepository.save(new FlashVoucher(
                "RLV20",
                DiscountType.PERCENTAGE,
                new BigDecimal("20.00"),
                new BigDecimal("10.00"),
                50,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                true,
                now
        ));

        // 1st call succeeds (201) with X-RateLimit-Limit=5, X-RateLimit-Remaining=4
        mockMvc.perform(post("/api/v1/vouchers/RLV20/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actorToken))
                .andExpect(status().isCreated())
                .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT, "5"))
                .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING, "4"));

        // Calls 2..5 hit duplicate claim (409) while draining remaining tokens (3..0)
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/vouchers/RLV20/claim")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + actorToken))
                    .andExpect(status().isConflict())
                    .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT, "5"))
                    .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING, String.valueOf(3 - i)));
        }

        // 6th call triggers 429 Too Many Requests with Retry-After and RFC 7807 ProblemDetail
        MvcResult limitedResult = mockMvc.perform(post("/api/v1/vouchers/RLV20/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actorToken)
                        .header(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-rl-429"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT, "5"))
                .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-rl-429"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.requestId").value("trace-rl-429"))
                .andReturn();

        long retryAfter = Long.parseLong(limitedResult.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isGreaterThanOrEqualTo(1L);

        // Distinct actor retains independent quota
        mockMvc.perform(post("/api/v1/vouchers/RLV20/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isCreated())
                .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT, "5"))
                .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING, "4"));

        MetricsSummaryResponse after = readMetricsSummary();
        assertThat(after.rateLimitRejected() - before.rateLimitRejected()).isEqualTo(1L);
    }

    @Test
    void orderCheckoutEnforcesPerActorRateLimitAndExposesHeaders() throws Exception {
        MetricsSummaryResponse before = readMetricsSummary();
        Instant now = clock.instant();

        User burstBuyer = userRepository.save(new User(
                "rl-order-burst@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Order Burst Buyer",
                Role.ROLE_CUSTOMER,
                now
        ));
        User cleanBuyer = userRepository.save(new User(
                "rl-order-clean@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Clean Buyer",
                Role.ROLE_CUSTOMER,
                now
        ));
        String burstToken = jwtService.generateAccessToken(burstBuyer);
        String cleanToken = jwtService.generateAccessToken(cleanBuyer);

        Product product = productRepository.save(new Product(
                "FS-RL-01",
                "Rate Limited Mouse",
                "8K Sensor",
                "Peripherals",
                new BigDecimal("50.00"),
                true,
                100,
                now
        ));

        String body = "{\"items\":[{\"productId\":" + product.getId() + ",\"quantity\":1}]}";

        // Drain 35-token order bucket for burstBuyer (allow up to 40 requests in case 1 token refills during loop)
        boolean hit429 = false;
        for (int i = 0; i < 40; i++) {
            MvcResult res = mockMvc.perform(post("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + burstToken)
                            .header("Idempotency-Key", "rl-order-key-" + i)
                            .header(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-order-rl-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();

            int status = res.getResponse().getStatus();
            assertThat(res.getResponse().getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT)).isEqualTo("35");
            if (status == 429) {
                hit429 = true;
                assertThat(res.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
                assertThat(res.getResponse().getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING)).isEqualTo("0");
                assertThat(res.getResponse().getContentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                var problem = objectMapper.readTree(res.getResponse().getContentAsString());
                assertThat(problem.get("errorCode").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
                assertThat(problem.get("requestId").asText()).isEqualTo("trace-order-rl-" + i);
                break;
            }
            assertThat(status).isEqualTo(201);
            int remaining = Integer.parseInt(res.getResponse().getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING));
            assertThat(remaining).isBetween(0, 34);
        }
        assertThat(hit429).isTrue();

        // Separate user is unaffected
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cleanToken)
                        .header("Idempotency-Key", "rl-clean-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT, "35"))
                .andExpect(header().string(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING, "34"));

        MetricsSummaryResponse after = readMetricsSummary();
        assertThat(after.rateLimitRejected() - before.rateLimitRejected()).isEqualTo(1L);
    }

    private MetricsSummaryResponse readMetricsSummary() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/metrics/summary"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), MetricsSummaryResponse.class);
    }
}
