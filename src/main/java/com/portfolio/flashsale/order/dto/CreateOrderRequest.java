package com.portfolio.flashsale.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
        @NotEmpty(message = "Order must contain at least one item")
        @Valid
        List<OrderItemRequest> items,

        @Size(max = 50, message = "Voucher code must not exceed 50 characters")
        String voucherCode
) {
}
