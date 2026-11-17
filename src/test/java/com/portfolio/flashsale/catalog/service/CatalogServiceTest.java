package com.portfolio.flashsale.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.catalog.dto.AdjustInventoryRequest;
import com.portfolio.flashsale.catalog.dto.CreateProductRequest;
import com.portfolio.flashsale.catalog.dto.ProductResponse;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private ProductRepository productRepository;

    private CatalogService catalogService;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        catalogService = new CatalogService(productRepository, clock);
    }

    @Test
    void createProductRejectsDuplicateSku() {
        when(productRepository.existsBySkuIgnoreCase("FLASH-KEY-01")).thenReturn(true);

        CreateProductRequest request = new CreateProductRequest(
                "flash-key-01",
                "Mechanical Keyboard",
                "Hot-swap 75%",
                "Peripherals",
                new BigDecimal("89.99"),
                25,
                true
        );

        assertThatThrownBy(() -> catalogService.createProduct(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");

        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void adjustInventoryRejectsStaleVersion() {
        Product product = new Product(
                "FLASH-002",
                "Gaming Mouse",
                "Lightweight mouse",
                "Peripherals",
                new BigDecimal("49.00"),
                true,
                15,
                clock.instant()
        );
        ReflectionTestUtils.setField(product, "id", 1L);
        ReflectionTestUtils.setField(product.getInventory(), "version", 3L);

        when(productRepository.findWithInventoryById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> catalogService.adjustInventory(1L, new AdjustInventoryRequest(30, 1L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not match current version");
    }

    @Test
    void adjustInventoryRejectsQuantityLowerThanReserved() {
        Product product = new Product(
                "FLASH-003",
                "USB-C Hub",
                "7-in-1 hub",
                "Accessories",
                new BigDecimal("29.00"),
                true,
                10,
                clock.instant()
        );
        ReflectionTestUtils.setField(product, "id", 2L);
        ReflectionTestUtils.setField(product.getInventory(), "reservedQty", 4);
        ReflectionTestUtils.setField(product.getInventory(), "version", 0L);

        when(productRepository.findWithInventoryById(2L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> catalogService.adjustInventory(2L, new AdjustInventoryRequest(3, 0L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("lower than reserved");
    }

    @Test
    void adjustInventoryUpdatesAvailableQtyWhenVersionMatches() {
        Product product = new Product(
                "FLASH-004",
                "Webcam HD",
                "1080p",
                "Accessories",
                new BigDecimal("59.00"),
                true,
                10,
                clock.instant()
        );
        ReflectionTestUtils.setField(product, "id", 3L);
        ReflectionTestUtils.setField(product.getInventory(), "version", 2L);

        when(productRepository.findWithInventoryById(3L)).thenReturn(Optional.of(product));
        when(productRepository.saveAndFlush(product)).thenReturn(product);

        ProductResponse response = catalogService.adjustInventory(3L, new AdjustInventoryRequest(40, 2L));
        assertThat(response.availableQty()).isEqualTo(40);
        assertThat(response.sellableQty()).isEqualTo(40);
    }
}
