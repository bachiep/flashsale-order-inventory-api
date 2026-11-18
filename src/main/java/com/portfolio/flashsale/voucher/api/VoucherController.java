package com.portfolio.flashsale.voucher.api;

import com.portfolio.flashsale.auth.security.AuthenticatedUser;
import com.portfolio.flashsale.voucher.dto.VoucherClaimResponse;
import com.portfolio.flashsale.voucher.dto.VoucherResponse;
import com.portfolio.flashsale.voucher.service.VoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vouchers")
@Tag(name = "Flash Vouchers", description = "Browse active flash vouchers and atomically claim limited quota")
public class VoucherController {

    private final VoucherService voucherService;

    public VoucherController(VoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @GetMapping("/active")
    @Operation(summary = "List currently active flash vouchers")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active flash vouchers retrieved")
    })
    public ResponseEntity<List<VoucherResponse>> listActiveVouchers() {
        return ResponseEntity.ok(voucherService.listActiveVouchers());
    }

    @PostMapping("/{code}/claim")
    @Operation(summary = "Atomically claim a flash voucher if quota remains")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Flash voucher claimed"),
            @ApiResponse(responseCode = "401", description = "Authentication required or JWT invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Voucher code not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Voucher inactive, already claimed by user, or quota exhausted",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<VoucherClaimResponse> claimVoucher(
            @PathVariable String code,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(voucherService.claimVoucher(currentUser.id(), code));
    }

    @GetMapping("/my-claims")
    @Operation(summary = "List flash vouchers claimed by the current user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user voucher claims retrieved"),
            @ApiResponse(responseCode = "401", description = "Authentication required or JWT invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<List<VoucherClaimResponse>> listMyClaims(
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ResponseEntity.ok(voucherService.listMyClaims(currentUser.id()));
    }
}
