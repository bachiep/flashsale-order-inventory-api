package com.portfolio.flashsale.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class FlashSaleMetrics {

    private final Counter ordersReservedCounter;
    private final Counter ordersConfirmedCounter;
    private final Counter ordersCancelledCounter;
    private final Counter ordersExpiredCounter;
    private final Counter oversellConflictsCounter;
    private final Counter idempotentReplaysCounter;
    private final Counter vouchersClaimedCounter;
    private final Counter voucherQuotaConflictsCounter;
    private final Clock clock;

    public FlashSaleMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.clock = clock;
        this.ordersReservedCounter = Counter.builder("flashsale.orders.reserved")
                .description("Total flash-sale orders created and inventory reserved")
                .register(meterRegistry);
        this.ordersConfirmedCounter = Counter.builder("flashsale.orders.confirmed")
                .description("Total flash-sale orders confirmed and stock permanently deducted")
                .register(meterRegistry);
        this.ordersCancelledCounter = Counter.builder("flashsale.orders.cancelled")
                .description("Total flash-sale orders cancelled with reserved stock released")
                .register(meterRegistry);
        this.ordersExpiredCounter = Counter.builder("flashsale.orders.expired")
                .description("Total pending orders expired past reservation TTL")
                .register(meterRegistry);
        this.oversellConflictsCounter = Counter.builder("flashsale.inventory.oversell.conflicts")
                .description("Total checkout attempts rejected due to insufficient sellable stock")
                .register(meterRegistry);
        this.idempotentReplaysCounter = Counter.builder("flashsale.orders.idempotent.replays")
                .description("Total order checkout requests safely replayed from idempotency snapshots")
                .register(meterRegistry);
        this.vouchersClaimedCounter = Counter.builder("flashsale.vouchers.claimed")
                .description("Total limited-quota flash voucher claims succeeded")
                .register(meterRegistry);
        this.voucherQuotaConflictsCounter = Counter.builder("flashsale.vouchers.quota.conflicts")
                .description("Total voucher claim attempts rejected due to exhausted quota")
                .register(meterRegistry);
    }

    public void recordOrderReserved() {
        ordersReservedCounter.increment();
    }

    public void recordOrderConfirmed() {
        ordersConfirmedCounter.increment();
    }

    public void recordOrderCancelled() {
        ordersCancelledCounter.increment();
    }

    public void recordOrderExpired() {
        ordersExpiredCounter.increment();
    }

    public void recordOversellConflict() {
        oversellConflictsCounter.increment();
    }

    public void recordIdempotentReplay() {
        idempotentReplaysCounter.increment();
    }

    public void recordVoucherClaimed() {
        vouchersClaimedCounter.increment();
    }

    public void recordVoucherQuotaConflict() {
        voucherQuotaConflictsCounter.increment();
    }

    public MetricsSummaryResponse snapshot() {
        return new MetricsSummaryResponse(
                Math.round(ordersReservedCounter.count()),
                Math.round(ordersConfirmedCounter.count()),
                Math.round(ordersCancelledCounter.count()),
                Math.round(ordersExpiredCounter.count()),
                Math.round(oversellConflictsCounter.count()),
                Math.round(idempotentReplaysCounter.count()),
                Math.round(vouchersClaimedCounter.count()),
                Math.round(voucherQuotaConflictsCounter.count()),
                clock.instant()
        );
    }
}
