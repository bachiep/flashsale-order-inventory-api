package com.portfolio.flashsale.order.api;

import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.security.AuthenticatedUser;
import com.portfolio.flashsale.common.dto.PagedResponse;
import com.portfolio.flashsale.order.dto.CreateOrderRequest;
import com.portfolio.flashsale.order.dto.OrderPlacementResult;
import com.portfolio.flashsale.order.dto.OrderResponse;
import com.portfolio.flashsale.order.service.OrderPlacementFacade;
import com.portfolio.flashsale.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Idempotent flash-sale order checkout and reservation lifecycle")
public class OrderController {

    private final OrderPlacementFacade orderPlacementFacade;
    private final OrderService orderService;

    public OrderController(OrderPlacementFacade orderPlacementFacade, OrderService orderService) {
        this.orderPlacementFacade = orderPlacementFacade;
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Create an order with atomic inventory reservation and Idempotency-Key protection")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created or safely replayed from idempotency snapshot"),
            @ApiResponse(responseCode = "400", description = "Missing Idempotency-Key or invalid request payload",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required or JWT invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Referenced product or voucher not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient sellable stock, idempotency payload mismatch, or voucher conflict",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Per-actor token-bucket rate limit exceeded (includes Retry-After header)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> createOrder(
            @Parameter(description = "Unique client-generated idempotency key per checkout attempt", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        OrderPlacementResult result = orderPlacementFacade.placeOrder(currentUser.id(), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Idempotency-Replayed", String.valueOf(result.replayed()))
                .body(result.order());
    }

    @GetMapping
    @Operation(summary = "List paginated orders belonging to the current user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated orders retrieved"),
            @ApiResponse(responseCode = "401", description = "Authentication required or JWT invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PagedResponse<OrderResponse>> listMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        int safePage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, 50));
        return ResponseEntity.ok(orderService.listMyOrders(currentUser.id(), PageRequest.of(safePage, boundedSize)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get order details (owner or admin only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order details retrieved"),
            @ApiResponse(responseCode = "400", description = "Invalid order ID format",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required or JWT invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Order belongs to another customer",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ResponseEntity.ok(orderService.getOrder(currentUser.id(), isAdmin(currentUser), id));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm payment and permanently deduct reserved inventory")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order payment confirmed and reserved stock permanently deducted"),
            @ApiResponse(responseCode = "401", description = "Authentication required or JWT invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Order belongs to another customer",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Order not in PENDING_PAYMENT state or reservation TTL expired",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> confirmOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ResponseEntity.ok(orderService.confirmOrder(currentUser.id(), isAdmin(currentUser), id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending order and release reserved inventory")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled and reserved stock released"),
            @ApiResponse(responseCode = "401", description = "Authentication required or JWT invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Order belongs to another customer",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Order cannot be cancelled from its current state",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ResponseEntity.ok(orderService.cancelOrder(currentUser.id(), isAdmin(currentUser), id));
    }

    private boolean isAdmin(AuthenticatedUser user) {
        return user.role() == Role.ROLE_ADMIN;
    }
}
