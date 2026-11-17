package com.portfolio.flashsale.catalog.repository;

import com.portfolio.flashsale.catalog.domain.Product;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    boolean existsBySkuIgnoreCase(String sku);

    @EntityGraph(attributePaths = "inventory")
    Optional<Product> findWithInventoryById(Long id);

    @Override
    @EntityGraph(attributePaths = "inventory")
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);
}
