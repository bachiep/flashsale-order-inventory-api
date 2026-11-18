package com.portfolio.flashsale.catalog.api;

import com.portfolio.flashsale.catalog.dto.ProductResponse;
import com.portfolio.flashsale.catalog.service.CatalogService;
import com.portfolio.flashsale.common.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Catalog", description = "Public flash-sale product catalog and stock availability")
public class ProductController {

    private final CatalogService catalogService;

    public ProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    @Operation(summary = "Search and paginate products with stock quantities")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated catalog products retrieved"),
            @ApiResponse(responseCode = "400", description = "Invalid query parameter type",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PagedResponse<ProductResponse>> listProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,asc") String sort
    ) {
        int safePage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(safePage, boundedSize, resolveSort(sort));
        return ResponseEntity.ok(catalogService.listProducts(q, category, activeOnly, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product details and real-time sellable quantity")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product details retrieved"),
            @ApiResponse(responseCode = "400", description = "Invalid product ID format",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Product not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getProductById(id));
    }

    private Sort resolveSort(String sortParam) {
        String[] tokens = sortParam.split(",", 2);
        String property = switch (tokens[0].trim()) {
            case "price", "name", "createdAt", "updatedAt" -> tokens[0].trim();
            default -> "id";
        };
        boolean descending = tokens.length > 1 && "desc".equalsIgnoreCase(tokens[1].trim());
        return Sort.by(descending ? Sort.Direction.DESC : Sort.Direction.ASC, property);
    }
}
