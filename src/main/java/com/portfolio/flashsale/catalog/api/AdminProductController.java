package com.portfolio.flashsale.catalog.api;

import com.portfolio.flashsale.catalog.dto.AdjustInventoryRequest;
import com.portfolio.flashsale.catalog.dto.CreateProductRequest;
import com.portfolio.flashsale.catalog.dto.ProductResponse;
import com.portfolio.flashsale.catalog.dto.UpdateProductRequest;
import com.portfolio.flashsale.catalog.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/products")
@Tag(name = "Admin Catalog", description = "Admin management for flash-sale products and inventory")
public class AdminProductController {

    private final CatalogService catalogService;

    public AdminProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    @Operation(summary = "Create a new product with initial inventory")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createProduct(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update product details and active state")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request
    ) {
        return ResponseEntity.ok(catalogService.updateProduct(id, request));
    }

    @PatchMapping("/{id}/inventory")
    @Operation(summary = "Adjust available inventory quantity with optimistic version check")
    public ResponseEntity<ProductResponse> adjustInventory(
            @PathVariable Long id,
            @Valid @RequestBody AdjustInventoryRequest request
    ) {
        return ResponseEntity.ok(catalogService.adjustInventory(id, request));
    }
}
