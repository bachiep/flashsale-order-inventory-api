package com.portfolio.flashsale.order.api;

import com.portfolio.flashsale.order.dto.OrderExpirySweepResponse;
import com.portfolio.flashsale.order.service.OrderExpiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Admin Orders", description = "Admin operational controls for reservation TTL expiry sweeps")
public class AdminOrderController {

    private final OrderExpiryService orderExpiryService;

    public AdminOrderController(OrderExpiryService orderExpiryService) {
        this.orderExpiryService = orderExpiryService;
    }

    @PostMapping("/expire-now")
    @Operation(summary = "Run an immediate reservation TTL expiry sweep and release abandoned stock holds")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation expiry sweep completed"),
            @ApiResponse(responseCode = "401", description = "Authentication required or JWT invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Admin role required",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Targeted order ID not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderExpirySweepResponse> expireOrdersNow(
            @Parameter(description = "When true, immediately expires active PENDING_PAYMENT holds without waiting for their TTL deadline")
            @RequestParam(defaultValue = "false") boolean includeActiveHolds,
            @Parameter(description = "Optional specific order ID to target during the sweep")
            @RequestParam(required = false) Long orderId
    ) {
        return ResponseEntity.ok(orderExpiryService.triggerManualSweep(includeActiveHolds, orderId));
    }
}
