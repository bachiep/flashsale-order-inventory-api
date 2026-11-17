package com.portfolio.flashsale.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank(message = "SKU is required")
        @Size(max = 64, message = "SKU must not exceed 64 characters")
        String sku,

        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name must not exceed 200 characters")
        String name,

        @Size(max = 1000, message = "Description must not exceed 1000 characters")
        String description,

        @NotBlank(message = "Category is required")
        @Size(max = 100, message = "Category must not exceed 100 characters")
        String category,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.00", message = "Price must be non-negative")
        BigDecimal price,

        @NotNull(message = "Initial stock is required")
        @Min(value = 0, message = "Initial stock must be non-negative")
        Integer initialStock,

        Boolean active
) {
}
