package com.portfolio.flashsale.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdjustInventoryRequest(
        @NotNull(message = "Available quantity is required")
        @Min(value = 0, message = "Available quantity must be non-negative")
        Integer availableQty,

        Long expectedVersion
) {
}
