package com.portfolio.flashsale.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Result of an administrative order reservation TTL expiry sweep")
public record OrderExpirySweepResponse(
        int expiredCount,
        boolean includeActiveHolds,
        Long targetedOrderId,
        Instant sweptAt
) {
}
