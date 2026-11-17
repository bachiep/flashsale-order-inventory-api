package com.portfolio.flashsale.order.dto;

public record OrderPlacementResult(
        OrderResponse order,
        boolean replayed
) {
}
