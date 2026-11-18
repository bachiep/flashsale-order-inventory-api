package com.portfolio.flashsale.order.service;

import com.portfolio.flashsale.catalog.service.CatalogService;
import com.portfolio.flashsale.common.config.AppProperties;
import com.portfolio.flashsale.common.observability.FlashSaleMetrics;
import com.portfolio.flashsale.inventory.repository.InventoryItemRepository;
import com.portfolio.flashsale.order.domain.Order;
import com.portfolio.flashsale.order.domain.OrderItem;
import com.portfolio.flashsale.order.repository.OrderRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderExpiryService {

    private static final int MAX_BATCHES_PER_TICK = 20;

    private final OrderRepository orderRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final VoucherClaimRepository voucherClaimRepository;
    private final CatalogService catalogService;
    private final AppProperties appProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final FlashSaleMetrics flashSaleMetrics;

    public OrderExpiryService(
            OrderRepository orderRepository,
            InventoryItemRepository inventoryItemRepository,
            VoucherClaimRepository voucherClaimRepository,
            CatalogService catalogService,
            AppProperties appProperties,
            Clock clock,
            PlatformTransactionManager transactionManager,
            FlashSaleMetrics flashSaleMetrics
    ) {
        this.orderRepository = orderRepository;
        this.inventoryItemRepository = inventoryItemRepository;
        this.voucherClaimRepository = voucherClaimRepository;
        this.catalogService = catalogService;
        this.appProperties = appProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.flashSaleMetrics = flashSaleMetrics;
    }

    public int expirePendingReservations() {
        int batchSize = Math.max(1, appProperties.scheduler().reservationExpiry().batchSize());
        int totalExpired = 0;

        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            Instant cutoff = clock.instant();
            List<Long> candidateIds = orderRepository.findExpiredPendingOrderIds(
                    cutoff,
                    PageRequest.of(0, batchSize)
            );
            if (candidateIds.isEmpty()) {
                break;
            }

            int expiredInBatch = 0;
            for (Long orderId : candidateIds) {
                Boolean expired = transactionTemplate.execute(status -> expireSingleOrder(orderId));
                if (Boolean.TRUE.equals(expired)) {
                    expiredInBatch++;
                    totalExpired++;
                    flashSaleMetrics.recordOrderExpired();
                }
            }

            if (candidateIds.size() < batchSize || expiredInBatch == 0) {
                break;
            }
        }

        if (totalExpired > 0) {
            catalogService.evictAllCatalogCaches();
        }
        return totalExpired;
    }

    private boolean expireSingleOrder(Long orderId) {
        Order order = orderRepository.findDetailedById(orderId).orElse(null);
        if (order == null) {
            return false;
        }

        Instant now = clock.instant();
        int updated = orderRepository.markExpiredIfPendingAndPastDeadline(orderId, now);
        if (updated == 0) {
            return false;
        }

        List<OrderItem> sortedItems = order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();
        for (OrderItem item : sortedItems) {
            inventoryItemRepository.releaseReservedStock(item.getProduct().getId(), item.getQuantity(), now);
        }

        if (order.getVoucher() != null) {
            voucherClaimRepository.restoreToClaimed(order.getVoucher().getId(), order.getUser().getId());
        }
        return true;
    }
}
