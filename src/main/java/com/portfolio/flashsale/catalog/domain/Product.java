package com.portfolio.flashsale.catalog.domain;

import com.portfolio.flashsale.inventory.domain.InventoryItem;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY, optional = false)
    private InventoryItem inventory;

    protected Product() {
    }

    public Product(
            String sku,
            String name,
            String description,
            String category,
            BigDecimal price,
            boolean active,
            int initialStock,
            Instant now
    ) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        this.active = active;
        this.createdAt = now;
        this.updatedAt = now;
        this.inventory = new InventoryItem(this, initialStock, now);
    }

    public void updateDetails(
            String name,
            String description,
            String category,
            BigDecimal price,
            boolean active,
            Instant now
    ) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        this.active = active;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public InventoryItem getInventory() {
        return inventory;
    }
}
