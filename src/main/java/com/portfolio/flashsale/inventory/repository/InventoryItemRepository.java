package com.portfolio.flashsale.inventory.repository;

import com.portfolio.flashsale.inventory.domain.InventoryItem;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findByProductId(Long productId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
            SET i.reservedQty = i.reservedQty + :qty,
                i.version = i.version + 1,
                i.updatedAt = :now
            WHERE i.product.id = :productId
              AND (i.availableQty - i.reservedQty) >= :qty
            """)
    int reserveStock(@Param("productId") Long productId, @Param("qty") int qty, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
            SET i.availableQty = i.availableQty - :qty,
                i.reservedQty = i.reservedQty - :qty,
                i.version = i.version + 1,
                i.updatedAt = :now
            WHERE i.product.id = :productId
              AND i.reservedQty >= :qty
              AND i.availableQty >= :qty
            """)
    int confirmReservedStock(@Param("productId") Long productId, @Param("qty") int qty, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
            SET i.reservedQty = i.reservedQty - :qty,
                i.version = i.version + 1,
                i.updatedAt = :now
            WHERE i.product.id = :productId
              AND i.reservedQty >= :qty
            """)
    int releaseReservedStock(@Param("productId") Long productId, @Param("qty") int qty, @Param("now") Instant now);
}
