package com.portfolio.flashsale.common.observability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Observability", description = "Live Micrometer counter snapshots for flash-sale operations")
public class MetricsController {

    private final FlashSaleMetrics flashSaleMetrics;

    public MetricsController(FlashSaleMetrics flashSaleMetrics) {
        this.flashSaleMetrics = flashSaleMetrics;
    }

    @GetMapping("/summary")
    @Operation(summary = "Get a live snapshot of flash-sale Micrometer counters")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Live Micrometer counter summary retrieved")
    })
    public ResponseEntity<MetricsSummaryResponse> summary() {
        return ResponseEntity.ok(flashSaleMetrics.snapshot());
    }
}
