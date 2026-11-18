package com.portfolio.flashsale.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        BucketPolicy orderCheckout,
        BucketPolicy voucherClaim
) {
    public RateLimitProperties {
        if (orderCheckout == null) {
            orderCheckout = new BucketPolicy(35, 35, 60L);
        }
        if (voucherClaim == null) {
            voucherClaim = new BucketPolicy(5, 5, 10L);
        }
    }

    public record BucketPolicy(
            int capacity,
            int refillTokens,
            long refillPeriodSeconds
    ) {
        public BucketPolicy {
            capacity = Math.max(1, capacity);
            refillTokens = Math.max(1, refillTokens);
            refillPeriodSeconds = Math.max(1L, refillPeriodSeconds);
        }
    }
}
