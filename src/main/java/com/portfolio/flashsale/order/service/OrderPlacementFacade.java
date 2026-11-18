package com.portfolio.flashsale.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.flashsale.catalog.service.CatalogService;
import com.portfolio.flashsale.common.exception.BadRequestException;
import com.portfolio.flashsale.common.exception.ConflictException;
import com.portfolio.flashsale.common.observability.FlashSaleMetrics;
import com.portfolio.flashsale.order.domain.IdempotencyRecord;
import com.portfolio.flashsale.order.dto.CreateOrderRequest;
import com.portfolio.flashsale.order.dto.OrderItemRequest;
import com.portfolio.flashsale.order.dto.OrderPlacementResult;
import com.portfolio.flashsale.order.dto.OrderResponse;
import com.portfolio.flashsale.order.repository.IdempotencyRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OrderPlacementFacade {

    private final OrderService orderService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final CatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final FlashSaleMetrics flashSaleMetrics;

    public OrderPlacementFacade(
            OrderService orderService,
            IdempotencyRecordRepository idempotencyRecordRepository,
            CatalogService catalogService,
            ObjectMapper objectMapper,
            FlashSaleMetrics flashSaleMetrics
    ) {
        this.orderService = orderService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    public OrderPlacementResult placeOrder(Long userId, String rawIdempotencyKey, CreateOrderRequest request) {
        if (!StringUtils.hasText(rawIdempotencyKey)) {
            throw new BadRequestException("MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header must not be blank");
        }
        String idempotencyKey = rawIdempotencyKey.trim();
        if (idempotencyKey.length() > 128) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key header must not exceed 128 characters");
        }

        Map<Long, Integer> sortedQuantities = aggregateAndSortItems(request);
        String normalizedVoucherCode = StringUtils.hasText(request.voucherCode())
                ? request.voucherCode().trim().toUpperCase(Locale.ROOT)
                : null;
        String requestHash = computeCanonicalHash(sortedQuantities, normalizedVoucherCode);

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return resolveExistingRecord(existing.get(), requestHash);
        }

        try {
            OrderResponse created = orderService.createOrderTransactionally(
                    userId,
                    idempotencyKey,
                    requestHash,
                    sortedQuantities,
                    normalizedVoucherCode
            );
            catalogService.evictAllCatalogCaches();
            flashSaleMetrics.recordOrderReserved();
            return new OrderPlacementResult(created, false);
        } catch (DataIntegrityViolationException ex) {
            Optional<IdempotencyRecord> racedRecord = idempotencyRecordRepository
                    .findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (racedRecord.isPresent()) {
                return resolveExistingRecord(racedRecord.get(), requestHash);
            }
            throw ex;
        }
    }

    private OrderPlacementResult resolveExistingRecord(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new ConflictException(
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "Idempotency-Key was already used with a different request payload"
            );
        }
        try {
            OrderResponse replayedOrder = objectMapper.readValue(record.getResponseBody(), OrderResponse.class);
            flashSaleMetrics.recordIdempotentReplay();
            return new OrderPlacementResult(replayedOrder, true);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize stored idempotency response", ex);
        }
    }

    private Map<Long, Integer> aggregateAndSortItems(CreateOrderRequest request) {
        Map<Long, Integer> sorted = new TreeMap<>();
        for (OrderItemRequest item : request.items()) {
            sorted.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return sorted;
    }

    private String computeCanonicalHash(Map<Long, Integer> sortedQuantities, String normalizedVoucherCode) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<Long, Integer> entry : sortedQuantities.entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        canonical.append("voucher=").append(normalizedVoucherCode == null ? "" : normalizedVoucherCode);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }
}
