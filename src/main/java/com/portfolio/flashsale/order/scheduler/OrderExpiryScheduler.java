package com.portfolio.flashsale.order.scheduler;

import com.portfolio.flashsale.order.service.OrderExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        value = "app.scheduler.reservation-expiry.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderExpiryService orderExpiryService;

    public OrderExpiryScheduler(OrderExpiryService orderExpiryService) {
        this.orderExpiryService = orderExpiryService;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.reservation-expiry.delay-ms:30000}")
    public void sweepExpiredReservations() {
        int expiredCount = orderExpiryService.expirePendingReservations();
        if (expiredCount > 0) {
            log.info("Expired {} unpaid order reservations and released reserved inventory", expiredCount);
        }
    }
}
