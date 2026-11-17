package com.portfolio.flashsale.catalog.service;

import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.catalog.dto.AdjustInventoryRequest;
import com.portfolio.flashsale.catalog.dto.CreateProductRequest;
import com.portfolio.flashsale.catalog.dto.ProductResponse;
import com.portfolio.flashsale.catalog.dto.UpdateProductRequest;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.catalog.repository.ProductSpecifications;
import com.portfolio.flashsale.common.config.CacheConfig;
import com.portfolio.flashsale.common.dto.PagedResponse;
import com.portfolio.flashsale.common.exception.ConflictException;
import com.portfolio.flashsale.common.exception.ResourceNotFoundException;
import com.portfolio.flashsale.inventory.domain.InventoryItem;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final Clock clock;

    public CatalogService(ProductRepository productRepository, Clock clock) {
        this.productRepository = productRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @Cacheable(
            cacheNames = CacheConfig.PRODUCTS_CACHE,
            key = "(#query ?: '') + ':' + (#category ?: '') + ':' + (#activeOnly ?: false) + ':' + #pageable.pageNumber + ':' + #pageable.pageSize + ':' + #pageable.sort"
    )
    public PagedResponse<ProductResponse> listProducts(
            String query,
            String category,
            Boolean activeOnly,
            Pageable pageable
    ) {
        Page<ProductResponse> resultPage = productRepository
                .findAll(ProductSpecifications.withFilters(query, category, activeOnly), pageable)
                .map(ProductResponse::from);
        return PagedResponse.from(resultPage);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.PRODUCT_BY_ID_CACHE, key = "#productId")
    public ProductResponse getProductById(Long productId) {
        return ProductResponse.from(loadProductWithInventory(productId));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, allEntries = true)
    public ProductResponse createProduct(CreateProductRequest request) {
        String normalizedSku = request.sku().trim().toUpperCase(Locale.ROOT);
        if (productRepository.existsBySkuIgnoreCase(normalizedSku)) {
            throw new ConflictException("DUPLICATE_SKU", "Product SKU already exists: " + normalizedSku);
        }

        Instant now = clock.instant();
        boolean active = request.active() == null || request.active();
        String trimmedDesc = request.description() == null ? null : request.description().trim();
        Product entity = new Product(
                normalizedSku,
                request.name().trim(),
                trimmedDesc,
                request.category().trim(),
                request.price(),
                active,
                request.initialStock(),
                now
        );
        return ProductResponse.from(productRepository.saveAndFlush(entity));
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_BY_ID_CACHE, key = "#productId")
    })
    public ProductResponse updateProduct(Long productId, UpdateProductRequest request) {
        Product entity = loadProductWithInventory(productId);
        String trimmedDesc = request.description() == null ? null : request.description().trim();
        entity.updateDetails(
                request.name().trim(),
                trimmedDesc,
                request.category().trim(),
                request.price(),
                request.active(),
                clock.instant()
        );
        return ProductResponse.from(productRepository.saveAndFlush(entity));
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_BY_ID_CACHE, key = "#productId")
    })
    public ProductResponse adjustInventory(Long productId, AdjustInventoryRequest request) {
        Product entity = loadProductWithInventory(productId);
        InventoryItem stockItem = entity.getInventory();
        if (request.expectedVersion() != null && !request.expectedVersion().equals(stockItem.getVersion())) {
            throw new ConflictException(
                    "CONCURRENT_MODIFICATION",
                    "Expected version " + request.expectedVersion() + " does not match current version " + stockItem.getVersion()
            );
        }

        stockItem.adjustAvailableQty(request.availableQty(), clock.instant());
        return ProductResponse.from(productRepository.saveAndFlush(entity));
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_BY_ID_CACHE, allEntries = true)
    })
    public void evictAllCatalogCaches() {
    }

    private Product loadProductWithInventory(Long productId) {
        return productRepository.findWithInventoryById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found: " + productId));
    }
}
