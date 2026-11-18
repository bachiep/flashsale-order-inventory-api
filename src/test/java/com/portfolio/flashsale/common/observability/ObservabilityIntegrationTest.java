package com.portfolio.flashsale.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.repository.RefreshTokenRepository;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.auth.security.JwtService;
import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.order.repository.IdempotencyRecordRepository;
import com.portfolio.flashsale.order.repository.OrderRepository;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
class ObservabilityIntegrationTest {

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
        MDC.clear();
    }

    @Test
    void correlationIdFilterGeneratesEchoesAndCleansMdc() throws Exception {
        MvcResult generatedResult = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.REQUEST_ID_HEADER))
                .andReturn();

        String generatedId = generatedResult.getResponse().getHeader(CorrelationIdFilter.REQUEST_ID_HEADER);
        assertThat(UUID.fromString(generatedId)).isNotNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_REQUEST_ID_KEY)).isNull();

        mockMvc.perform(get("/api/v1/health")
                        .header(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-health-001"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-health-001"));

        assertThat(MDC.get(CorrelationIdFilter.MDC_REQUEST_ID_KEY)).isNull();

        MvcResult sanitizedResult = mockMvc.perform(get("/api/v1/health")
                        .header(CorrelationIdFilter.REQUEST_ID_HEADER, "bad header with spaces!"))
                .andExpect(status().isOk())
                .andReturn();

        String fallbackId = sanitizedResult.getResponse().getHeader(CorrelationIdFilter.REQUEST_ID_HEADER);
        assertThat(UUID.fromString(fallbackId)).isNotNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_REQUEST_ID_KEY)).isNull();
    }

    @Test
    void problemDetailIncludesRequestIdAcrossSecurityAndMvcHandlers() throws Exception {
        Instant now = clock.instant();
        User customer = userRepository.save(new User(
                "obs-customer@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Obs Customer",
                Role.ROLE_CUSTOMER,
                now
        ));
        String customerToken = jwtService.generateAccessToken(customer);

        mockMvc.perform(get("/api/v1/orders")
                        .header(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-sec-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-sec-401"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").value("trace-sec-401"));

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-sec-403")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-sec-403"))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").value("trace-sec-403"));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-mvc-400")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-mvc-400"))
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.requestId").value("trace-mvc-400"));

        MvcResult notFoundResult = mockMvc.perform(get("/api/v1/products/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                .andReturn();

        String headerId = notFoundResult.getResponse().getHeader(CorrelationIdFilter.REQUEST_ID_HEADER);
        JsonNode problemBody = objectMapper.readTree(notFoundResult.getResponse().getContentAsString());
        assertThat(headerId).isNotBlank();
        assertThat(problemBody.get("requestId").asText()).isEqualTo(headerId);
    }

    @Test
    void metricsSummaryReflectsLiveOrderAndVoucherCounters() throws Exception {
        MetricsSummaryResponse before = readMetricsSummary();

        Instant now = clock.instant();
        User buyer1 = userRepository.save(new User(
                "metrics-buyer1@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Metrics Buyer 1",
                Role.ROLE_CUSTOMER,
                now
        ));
        User buyer2 = userRepository.save(new User(
                "metrics-buyer2@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Metrics Buyer 2",
                Role.ROLE_CUSTOMER,
                now
        ));
        String token1 = jwtService.generateAccessToken(buyer1);
        String token2 = jwtService.generateAccessToken(buyer2);

        Product product = productRepository.save(new Product(
                "FS-METRIC-1",
                "Telemetry Keyboard",
                "Limited edition",
                "Peripherals",
                new BigDecimal("100.00"),
                true,
                2,
                now
        ));
        voucherRepository.save(new FlashVoucher(
                "METRIC10",
                DiscountType.FIXED_AMOUNT,
                new BigDecimal("10.00"),
                new BigDecimal("50.00"),
                1,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                true,
                now
        ));

        mockMvc.perform(post("/api/v1/vouchers/METRIC10/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/vouchers/METRIC10/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VOUCHER_QUOTA_EXHAUSTED"));

        String orderBody = "{\"items\":[{\"productId\":" + product.getId() + ",\"quantity\":2}]}";

        MvcResult created1 = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1)
                        .header("Idempotency-Key", "metrics-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andReturn();
        Long order1Id = objectMapper.readTree(created1.getResponse().getContentAsString()).get("id").asLong();

        // Replay same key
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1)
                        .header("Idempotency-Key", "metrics-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotency-Replayed", "true"));

        // Oversell conflict while stock is 0 sellable
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token2)
                        .header("Idempotency-Key", "metrics-key-oversell")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"));

        // Cancel order1 -> releases 2 stock
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", order1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token1))
                .andExpect(status().isOk());

        // Second buyer places order and confirms it
        MvcResult created2 = mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token2)
                        .header("Idempotency-Key", "metrics-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andReturn();
        Long order2Id = objectMapper.readTree(created2.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/orders/{id}/confirm", order2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token2))
                .andExpect(status().isOk());

        MetricsSummaryResponse after = readMetricsSummary();
        assertThat(after.vouchersClaimed() - before.vouchersClaimed()).isEqualTo(1L);
        assertThat(after.voucherQuotaConflicts() - before.voucherQuotaConflicts()).isEqualTo(1L);
        assertThat(after.ordersReserved() - before.ordersReserved()).isEqualTo(2L);
        assertThat(after.idempotentReplays() - before.idempotentReplays()).isEqualTo(1L);
        assertThat(after.oversellConflicts() - before.oversellConflicts()).isEqualTo(1L);
        assertThat(after.ordersCancelled() - before.ordersCancelled()).isEqualTo(1L);
        assertThat(after.ordersConfirmed() - before.ordersConfirmed()).isEqualTo(1L);
        assertThat(after.timestamp()).isNotNull();
    }

    @Test
    void openApiDocsIncludeApiResponsesAndProblemDetailSchemas() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ProblemDetail").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['201'].content['application/json'].schema.$ref").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['409'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['400'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/orders/{id}'].get.responses['403'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/vouchers/{code}/claim'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/vouchers/{code}/claim'].post.responses['409'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['401'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/products/{id}'].get.responses['404'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/products/{id}/inventory'].patch.responses['409'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/metrics/summary'].get.responses['200']").exists())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("info").path("version").asText()).isEqualTo("v2.0");
    }

    @Test
    void rootAndIndexHtmlServeInteractivePlaygroundWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("FlashSale Order &amp; Inventory Console v2.0")))
                .andExpect(content().string(containsString("/swagger-ui/index.html")))
                .andExpect(content().string(containsString("/api/v1/metrics/summary")));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Fire 30 Concurrent Buyers on Limited SKU")));
    }

    private MetricsSummaryResponse readMetricsSummary() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/metrics/summary"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), MetricsSummaryResponse.class);
    }
}
