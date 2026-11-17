package com.portfolio.flashsale.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.catalog.service.CatalogService;
import com.portfolio.flashsale.common.config.AppProperties;
import com.portfolio.flashsale.common.dto.PagedResponse;
import com.portfolio.flashsale.common.exception.ConflictException;
import com.portfolio.flashsale.common.exception.ForbiddenException;
import com.portfolio.flashsale.common.exception.ResourceNotFoundException;
import com.portfolio.flashsale.inventory.repository.InventoryItemRepository;
import com.portfolio.flashsale.order.domain.IdempotencyRecord;
import com.portfolio.flashsale.order.domain.Order;
import com.portfolio.flashsale.order.domain.OrderItem;
import com.portfolio.flashsale.order.domain.OrderStatus;
import com.portfolio.flashsale.order.dto.OrderResponse;
import com.portfolio.flashsale.order.repository.IdempotencyRecordRepository;
import com.portfolio.flashsale.order.repository.OrderRepository;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ProductRepository productRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final FlashVoucherRepository voucherRepository;
    private final VoucherClaimRepository voucherClaimRepository;
    private final UserRepository userRepository;
    private final CatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final Clock clock;

    public OrderService(
            OrderRepository orderRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            ProductRepository productRepository,
            InventoryItemRepository inventoryItemRepository,
            FlashVoucherRepository voucherRepository,
            VoucherClaimRepository voucherClaimRepository,
            UserRepository userRepository,
            CatalogService catalogService,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.productRepository = productRepository;
        this.inventoryItemRepository = inventoryItemRepository;
        this.voucherRepository = voucherRepository;
        this.voucherClaimRepository = voucherClaimRepository;
        this.userRepository = userRepository;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse createOrderTransactionally(
            Long userId,
            String idempotencyKey,
            String requestHash,
            Map<Long, Integer> sortedQuantities,
            String normalizedVoucherCode
    ) {
        Instant now = clock.instant();
        Map<Long, Product> productsById = productRepository.findAllById(sortedQuantities.keySet())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        for (Long productId : sortedQuantities.keySet()) {
            Product product = productsById.get(productId);
            if (product == null) {
                throw new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found: " + productId);
            }
            if (!product.isActive()) {
                throw new ConflictException("PRODUCT_INACTIVE", "Product is not active: " + product.getSku());
            }
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : sortedQuantities.entrySet()) {
            Long productId = entry.getKey();
            int qty = entry.getValue();
            int reservedRows = inventoryItemRepository.reserveStock(productId, qty, now);
            if (reservedRows == 0) {
                Product product = productsById.get(productId);
                throw new ConflictException(
                        "INSUFFICIENT_STOCK",
                        "Insufficient available stock for product " + product.getSku()
                );
            }
            Product product = productsById.get(productId);
            subtotal = subtotal.add(product.getPrice().multiply(BigDecimal.valueOf(qty)));
        }

        FlashVoucher appliedVoucher = null;
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (normalizedVoucherCode != null) {
            appliedVoucher = applyClaimedVoucher(userId, normalizedVoucherCode, subtotal, now);
            discountAmount = appliedVoucher.calculateDiscount(subtotal);
        }

        BigDecimal totalAmount = subtotal.subtract(discountAmount).max(BigDecimal.ZERO);
        Instant expiresAt = now.plusSeconds(appProperties.order().reservationTtlSeconds());
        User userRef = userRepository.getReferenceById(userId);
        FlashVoucher voucherRef = appliedVoucher == null ? null : voucherRepository.getReferenceById(appliedVoucher.getId());

        Order order = new Order(
                generateOrderNumber(),
                userRef,
                voucherRef,
                OrderStatus.PENDING_PAYMENT,
                subtotal,
                discountAmount,
                totalAmount,
                expiresAt,
                now
        );

        for (Map.Entry<Long, Integer> entry : sortedQuantities.entrySet()) {
            Product product = productsById.get(entry.getKey());
            int qty = entry.getValue();
            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(qty));
            order.addItem(new OrderItem(
                    productRepository.getReferenceById(product.getId()),
                    product.getSku(),
                    product.getName(),
                    qty,
                    product.getPrice(),
                    lineTotal
            ));
        }

        Order savedOrder = orderRepository.saveAndFlush(order);
        OrderResponse response = toOrderResponse(
                savedOrder,
                appliedVoucher == null ? null : appliedVoucher.getCode()
        );
        String serializedResponse = serializeOrderResponse(response);

        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord(
                userRef,
                idempotencyKey,
                requestHash,
                HttpStatus.CREATED.value(),
                serializedResponse,
                savedOrder,
                now
        ));

        return response;
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long userId, boolean isAdmin, Long orderId) {
        Order order = loadAuthorizedOrder(userId, isAdmin, orderId);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PagedResponse<OrderResponse> listMyOrders(Long userId, Pageable pageable) {
        Page<Long> idPage = orderRepository.findOrderIdsByUserId(userId, pageable);
        if (idPage.isEmpty()) {
            return new PagedResponse<>(List.of(), idPage.getNumber(), idPage.getSize(), 0, 0, true);
        }

        Map<Long, Order> detailedById = orderRepository.findAllDetailedByIdIn(idPage.getContent())
                .stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));

        List<OrderResponse> items = idPage.getContent().stream()
                .map(detailedById::get)
                .map(OrderResponse::from)
                .toList();

        return new PagedResponse<>(
                items,
                idPage.getNumber(),
                idPage.getSize(),
                idPage.getTotalElements(),
                idPage.getTotalPages(),
                idPage.isLast()
        );
    }

    @Transactional
    public OrderResponse confirmOrder(Long userId, boolean isAdmin, Long orderId) {
        Order order = loadAuthorizedOrder(userId, isAdmin, orderId);
        Instant now = clock.instant();

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ConflictException(
                    "INVALID_ORDER_STATE",
                    "Order cannot be confirmed from status " + order.getStatus()
            );
        }
        if (!order.getExpiresAt().isAfter(now)) {
            throw new ConflictException(
                    "ORDER_RESERVATION_EXPIRED",
                    "Order reservation has expired and can no longer be confirmed"
            );
        }

        int updated = orderRepository.markConfirmedIfPendingAndNotExpired(orderId, now);
        if (updated == 0) {
            throw new ConflictException("INVALID_ORDER_STATE", "Order could not be confirmed");
        }

        List<OrderItem> sortedItems = order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();
        for (OrderItem item : sortedItems) {
            int confirmed = inventoryItemRepository.confirmReservedStock(
                    item.getProduct().getId(),
                    item.getQuantity(),
                    now
            );
            if (confirmed == 0) {
                throw new ConflictException("INVENTORY_CONFIRM_FAILED", "Reserved inventory state mismatch");
            }
        }

        catalogService.evictAllCatalogCaches();
        return OrderResponse.from(orderRepository.findDetailedById(orderId).orElseThrow());
    }

    @Transactional
    public OrderResponse cancelOrder(Long userId, boolean isAdmin, Long orderId) {
        Order order = loadAuthorizedOrder(userId, isAdmin, orderId);
        Instant now = clock.instant();

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ConflictException(
                    "INVALID_ORDER_STATE",
                    "Only PENDING_PAYMENT orders can be cancelled (current: " + order.getStatus() + ")"
            );
        }

        int updated = orderRepository.markCancelledIfPending(orderId, now);
        if (updated == 0) {
            throw new ConflictException("INVALID_ORDER_STATE", "Order could not be cancelled");
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

        catalogService.evictAllCatalogCaches();
        return OrderResponse.from(orderRepository.findDetailedById(orderId).orElseThrow());
    }

    private FlashVoucher applyClaimedVoucher(Long userId, String code, BigDecimal subtotal, Instant now) {
        FlashVoucher voucher = voucherRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new ResourceNotFoundException("VOUCHER_NOT_FOUND", "Voucher not found: " + code));

        if (!voucher.isWithinActiveWindow(now)) {
            throw new ConflictException("VOUCHER_NOT_ACTIVE", "Voucher is not active in the current time window");
        }
        if (subtotal.compareTo(voucher.getMinOrderAmount()) < 0) {
            throw new ConflictException(
                    "VOUCHER_MIN_ORDER_NOT_MET",
                    "Minimum order amount for voucher " + voucher.getCode() + " is " + voucher.getMinOrderAmount()
            );
        }

        int appliedRows = voucherClaimRepository.markAppliedIfClaimed(voucher.getId(), userId, now);
        if (appliedRows == 0) {
            throw new ConflictException(
                    "VOUCHER_NOT_AVAILABLE",
                    "Voucher " + voucher.getCode() + " must be claimed and unused before applying"
            );
        }
        return voucher;
    }

    private Order loadAuthorizedOrder(Long userId, boolean isAdmin, Long orderId) {
        Order order = orderRepository.findDetailedById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found: " + orderId));
        if (!isAdmin && !order.getUser().getId().equals(userId)) {
            throw new ForbiddenException("ORDER_ACCESS_DENIED", "You do not have permission to access this order");
        }
        return order;
    }

    private OrderResponse toOrderResponse(Order order, String voucherCode) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                voucherCode,
                order.getSubtotalAmount(),
                order.getDiscountAmount(),
                order.getTotalAmount(),
                order.getItems().stream()
                        .map(item -> new com.portfolio.flashsale.order.dto.OrderItemResponse(
                                item.getProduct().getId(),
                                item.getProductSku(),
                                item.getProductName(),
                                item.getQuantity(),
                                item.getUnitPrice(),
                                item.getLineTotal()
                        ))
                        .toList(),
                order.getExpiresAt(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getCreatedAt()
        );
    }

    private String serializeOrderResponse(OrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize order response snapshot", ex);
        }
    }

    private String generateOrderNumber() {
        return "FS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
