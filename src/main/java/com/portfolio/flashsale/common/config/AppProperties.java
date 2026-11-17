package com.portfolio.flashsale.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Order order,
        Scheduler scheduler,
        Seed seed
) {
    public record Jwt(
            String secret,
            long accessExpirationSeconds,
            long refreshExpirationSeconds
    ) {
    }

    public record Order(
            long reservationTtlSeconds
    ) {
    }

    public record Scheduler(
            ReservationExpiry reservationExpiry
    ) {
        public record ReservationExpiry(
                boolean enabled,
                long delayMs,
                int batchSize
        ) {
        }
    }

    public record Seed(
            boolean enabled
    ) {
    }
}
