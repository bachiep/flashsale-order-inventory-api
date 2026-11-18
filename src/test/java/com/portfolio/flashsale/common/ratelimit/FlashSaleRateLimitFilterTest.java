package com.portfolio.flashsale.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.security.AuthenticatedUser;
import com.portfolio.flashsale.common.observability.CorrelationIdFilter;
import com.portfolio.flashsale.common.observability.FlashSaleMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class FlashSaleRateLimitFilterTest {

    private MutableClock clock;
    private FlashSaleMetrics metrics;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        metrics = new FlashSaleMetrics(new SimpleMeterRegistry(), clock);
        objectMapper = new ObjectMapper().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enforcesTokenBucketRefillUsingInjectedClock() throws Exception {
        RateLimitProperties props = new RateLimitProperties(
                true,
                new RateLimitProperties.BucketPolicy(10, 10, 10),
                new RateLimitProperties.BucketPolicy(2, 2, 10)
        );
        FlashSaleRateLimitFilter filter = new FlashSaleRateLimitFilter(props, metrics, objectMapper, clock);
        setAuthUser(42L);

        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (req, res) -> passed.incrementAndGet();

        // 2 allowed
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = post("/api/v1/vouchers/FLASH20/claim")
                    .header(CorrelationIdFilter.REQUEST_ID_HEADER, "rl-unit-" + i)
                    .buildRequest(null);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(res.getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT)).isEqualTo("2");
            assertThat(res.getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING)).isEqualTo(String.valueOf(1 - i));
        }
        assertThat(passed.get()).isEqualTo(2);

        // 3rd blocked with 429 ProblemDetail
        MockHttpServletRequest blockedReq = post("/api/v1/vouchers/FLASH20/claim")
                .header(CorrelationIdFilter.REQUEST_ID_HEADER, "rl-unit-blocked")
                .buildRequest(null);
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        filter.doFilter(blockedReq, blockedRes, chain);

        assertThat(blockedRes.getStatus()).isEqualTo(429);
        assertThat(blockedRes.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(blockedRes.getHeader("Retry-After")).isEqualTo("5");
        assertThat(blockedRes.getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING)).isEqualTo("0");
        assertThat(metrics.snapshot().rateLimitRejected()).isEqualTo(1L);

        JsonNode problem = objectMapper.readTree(blockedRes.getContentAsString());
        assertThat(problem.get("status").asInt()).isEqualTo(429);
        assertThat(problem.get("errorCode").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(problem.get("requestId").asText()).isEqualTo("rl-unit-blocked");

        // Advance clock by 5 seconds -> 1 token refilled
        clock.advanceSeconds(5);
        MockHttpServletRequest refillReq = post("/api/v1/vouchers/FLASH20/claim").buildRequest(null);
        MockHttpServletResponse refillRes = new MockHttpServletResponse();
        filter.doFilter(refillReq, refillRes, chain);

        assertThat(refillRes.getStatus()).isEqualTo(200);
        assertThat(passed.get()).isEqualTo(3);
    }

    @Test
    void bypassesUnprotectedRoutesGetRequestsAndDisabledFlag() throws Exception {
        RateLimitProperties disabledProps = new RateLimitProperties(
                false,
                new RateLimitProperties.BucketPolicy(1, 1, 60),
                new RateLimitProperties.BucketPolicy(1, 1, 60)
        );
        FlashSaleRateLimitFilter disabledFilter = new FlashSaleRateLimitFilter(disabledProps, metrics, objectMapper, clock);
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (req, res) -> passed.incrementAndGet();

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = post("/api/v1/orders").buildRequest(null);
            MockHttpServletResponse res = new MockHttpServletResponse();
            disabledFilter.doFilter(req, res, chain);
            assertThat(res.getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT)).isNull();
        }
        assertThat(passed.get()).isEqualTo(3);

        RateLimitProperties enabledProps = new RateLimitProperties(
                true,
                new RateLimitProperties.BucketPolicy(1, 1, 60),
                new RateLimitProperties.BucketPolicy(1, 1, 60)
        );
        FlashSaleRateLimitFilter enabledFilter = new FlashSaleRateLimitFilter(enabledProps, metrics, objectMapper, clock);

        MockHttpServletRequest getOrders = get("/api/v1/orders").buildRequest(null);
        MockHttpServletResponse getRes = new MockHttpServletResponse();
        enabledFilter.doFilter(getOrders, getRes, chain);
        assertThat(getRes.getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT)).isNull();

        MockHttpServletRequest unauthLogin = post("/api/v1/auth/login").buildRequest(null);
        MockHttpServletResponse loginRes = new MockHttpServletResponse();
        enabledFilter.doFilter(unauthLogin, loginRes, chain);
        assertThat(loginRes.getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT)).isNull();

        // Anonymous fallback uses X-Forwarded-For first hop when valid
        MockHttpServletRequest ip1Req = post("/api/v1/orders").header("X-Forwarded-For", "198.51.100.10, 10.0.0.1").buildRequest(null);
        MockHttpServletResponse ip1Res = new MockHttpServletResponse();
        enabledFilter.doFilter(ip1Req, ip1Res, chain);
        assertThat(ip1Res.getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_LIMIT)).isEqualTo("1");

        MockHttpServletRequest ip2Req = post("/api/v1/orders").header("X-Forwarded-For", "198.51.100.11").buildRequest(null);
        MockHttpServletResponse ip2Res = new MockHttpServletResponse();
        enabledFilter.doFilter(ip2Req, ip2Res, chain);
        assertThat(ip2Res.getStatus()).isEqualTo(200);
        assertThat(ip2Res.getHeader(FlashSaleRateLimitFilter.HEADER_RATE_LIMIT_REMAINING)).isEqualTo("0");
    }

    private void setAuthUser(Long userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "user" + userId + "@flashsale.local", Role.ROLE_CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority(Role.ROLE_CUSTOMER.name()))
                )
        );
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advanceSeconds(long seconds) {
            current.updateAndGet(now -> now.plusSeconds(seconds));
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
