package com.portfolio.flashsale.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.flashsale.auth.security.AuthenticatedUser;
import com.portfolio.flashsale.common.observability.CorrelationIdFilter;
import com.portfolio.flashsale.common.observability.FlashSaleMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class FlashSaleRateLimitFilter extends OncePerRequestFilter {

    public static final String HEADER_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    public static final String ERROR_CODE = "RATE_LIMIT_EXCEEDED";

    private static final String ORDERS_PATH = "/api/v1/orders";
    private static final Pattern VOUCHER_CLAIM_PATTERN = Pattern.compile("^/api/v1/vouchers/[^/]+/claim$");
    private static final Pattern SAFE_IP_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9.:_-]{1,64}$");
    private static final int MAX_TRACKED_BUCKETS = 10_000;

    private final RateLimitProperties properties;
    private final FlashSaleMetrics flashSaleMetrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public FlashSaleRateLimitFilter(
            RateLimitProperties properties,
            FlashSaleMetrics flashSaleMetrics,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.flashSaleMetrics = flashSaleMetrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.enabled() || !HttpMethod.POST.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        RouteGuard guard = matchGuardedRoute(resolveApplicationPath(request));
        if (guard == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long nowMillis = clock.instant().toEpochMilli();
        evictStaleBucketsIfNeeded(nowMillis, guard.policy());

        String bucketKey = guard.routeName() + ":" + resolveActorKey(request);
        TokenBucket bucket = buckets.computeIfAbsent(
                bucketKey,
                ignored -> new TokenBucket(guard.policy(), nowMillis)
        );
        ConsumptionProbe probe = bucket.tryConsume(guard.policy(), nowMillis);

        response.setHeader(HEADER_RATE_LIMIT_LIMIT, String.valueOf(probe.limit()));
        response.setHeader(HEADER_RATE_LIMIT_REMAINING, String.valueOf(probe.remaining()));

        if (probe.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        flashSaleMetrics.recordRateLimitRejected();
        writeTooManyRequestsProblem(request, response, probe.retryAfterSeconds());
    }

    public void clearBuckets() {
        buckets.clear();
    }

    private RouteGuard matchGuardedRoute(String path) {
        if (ORDERS_PATH.equals(path)) {
            return new RouteGuard("orders", properties.orderCheckout());
        }
        if (VOUCHER_CLAIM_PATTERN.matcher(path).matches()) {
            return new RouteGuard("voucher-claim", properties.voucherClaim());
        }
        return null;
    }

    private String resolveApplicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private String resolveActorKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user && user.id() != null) {
            return "user:" + user.id();
        }
        return "ip:" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            int commaIdx = forwardedFor.indexOf(',');
            String firstHop = (commaIdx >= 0 ? forwardedFor.substring(0, commaIdx) : forwardedFor).trim();
            if (SAFE_IP_TOKEN_PATTERN.matcher(firstHop).matches()) {
                return firstHop;
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddr) ? remoteAddr.trim() : "unknown";
    }

    private void evictStaleBucketsIfNeeded(long nowMillis, RateLimitProperties.BucketPolicy policy) {
        if (buckets.size() < MAX_TRACKED_BUCKETS) {
            return;
        }
        long staleCutoffMillis = nowMillis - (Math.max(1L, policy.refillPeriodSeconds()) * 2_000L);
        buckets.entrySet().removeIf(entry -> entry.getValue().lastRefillMillis() < staleCutoffMillis);
    }

    private void writeTooManyRequestsProblem(
            HttpServletRequest request,
            HttpServletResponse response,
            long retryAfterSeconds
    ) throws IOException {
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        String detail = "Rate limit exceeded for flash-sale endpoint; retry after " + retryAfterSeconds + " seconds";

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://api.flashsale.local/problems/rate-limit-exceeded"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", ERROR_CODE);
        problem.setProperty("requestId", CorrelationIdFilter.resolveOrGenerateRequestId(request));
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        problem.setProperty("timestamp", clock.instant().toString());

        response.setStatus(status.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private record RouteGuard(String routeName, RateLimitProperties.BucketPolicy policy) {
    }

    record ConsumptionProbe(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    static final class TokenBucket {
        private double availableTokens;
        private long lastRefillMillis;

        TokenBucket(RateLimitProperties.BucketPolicy policy, long nowMillis) {
            this.availableTokens = policy.capacity();
            this.lastRefillMillis = nowMillis;
        }

        synchronized ConsumptionProbe tryConsume(RateLimitProperties.BucketPolicy policy, long nowMillis) {
            int capacity = policy.capacity();
            double refillRatePerMillis = (double) policy.refillTokens()
                    / (double) (Math.max(1L, policy.refillPeriodSeconds()) * 1_000L);

            refill(capacity, refillRatePerMillis, nowMillis);

            if (availableTokens >= 1.0d) {
                availableTokens -= 1.0d;
                int remaining = Math.max(0, (int) Math.floor(availableTokens));
                return new ConsumptionProbe(true, capacity, remaining, 0L);
            }

            double deficit = 1.0d - availableTokens;
            long millisToWait = (long) Math.ceil(deficit / refillRatePerMillis);
            long retryAfterSeconds = Math.max(1L, (long) Math.ceil(millisToWait / 1_000.0d));
            return new ConsumptionProbe(false, capacity, 0, retryAfterSeconds);
        }

        synchronized long lastRefillMillis() {
            return lastRefillMillis;
        }

        private void refill(int capacity, double refillRatePerMillis, long nowMillis) {
            if (nowMillis < lastRefillMillis) {
                lastRefillMillis = nowMillis;
                availableTokens = Math.min(capacity, availableTokens);
                return;
            }
            long elapsedMillis = nowMillis - lastRefillMillis;
            if (elapsedMillis <= 0L) {
                availableTokens = Math.min(capacity, availableTokens);
                return;
            }
            availableTokens = Math.min(capacity, availableTokens + (elapsedMillis * refillRatePerMillis));
            lastRefillMillis = nowMillis;
        }
    }
}
