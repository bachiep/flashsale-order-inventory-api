package com.portfolio.flashsale.inventory.domain;

import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryItem() {
    }

    public InventoryItem(Product product, int availableQty, Instant updatedAt) {
        if (availableQty < 0) {
            throw new IllegalArgumentException("availableQty must not be negative");
        }
        this.product = product;
        this.availableQty = availableQty;
        this.reservedQty = 0;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public int getAvailableQty() {
        return availableQty;
    }

    public int getReservedQty() {
        return reservedQty;
    }

    public int getSellableQty() {
        return Math.max(0, availableQty - reservedQty);
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void adjustAvailableQty(int newAvailableQty, Instant now) {
        if (newAvailableQty < reservedQty) {
            throw new ConflictException(
                    "INVENTORY_BELOW_RESERVED",
                    "Available quantity (" + newAvailableQty + ") cannot be lower than reserved quantity (" + reservedQty + ")"
            );
        }
        this.availableQty = newAvailableQty;
        this.updatedAt = now;
    }
}
