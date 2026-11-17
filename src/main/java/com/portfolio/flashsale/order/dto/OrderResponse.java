package com.portfolio.flashsale.order.dto;

import com.portfolio.flashsale.order.domain.Order;
import com.portfolio.flashsale.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        String voucherCode,
        BigDecimal subtotalAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant expiresAt,
        Instant confirmedAt,
        Instant cancelledAt,
        Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getVoucher() == null ? null : order.getVoucher().getCode(),
                order.getSubtotalAmount(),
                order.getDiscountAmount(),
                order.getTotalAmount(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getExpiresAt(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getCreatedAt()
        );
    }
}
