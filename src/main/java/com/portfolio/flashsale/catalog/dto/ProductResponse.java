package com.portfolio.flashsale.catalog.dto;

import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.inventory.domain.InventoryItem;
import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        String category,
        BigDecimal price,
        boolean active,
        int availableQty,
        int reservedQty,
        int sellableQty,
        Long inventoryVersion,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product product) {
        InventoryItem inventory = product.getInventory();
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getCategory(),
                product.getPrice(),
                product.isActive(),
                inventory.getAvailableQty(),
                inventory.getReservedQty(),
                inventory.getSellableQty(),
                inventory.getVersion(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
