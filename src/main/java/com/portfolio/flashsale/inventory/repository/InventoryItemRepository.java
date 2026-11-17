package com.portfolio.flashsale.inventory.repository;

import com.portfolio.flashsale.inventory.domain.InventoryItem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findByProductId(Long productId);
}
