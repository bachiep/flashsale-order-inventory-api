package com.portfolio.flashsale.common.observability;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Live Micrometer counter snapshot for flash-sale orders, inventory contention, and voucher claims")
public record MetricsSummaryResponse(
        long ordersReserved,
        long ordersConfirmed,
        long ordersCancelled,
        long ordersExpired,
        long oversellConflicts,
        long idempotentReplays,
        long vouchersClaimed,
        long voucherQuotaConflicts,
        Instant timestamp
) {
}
