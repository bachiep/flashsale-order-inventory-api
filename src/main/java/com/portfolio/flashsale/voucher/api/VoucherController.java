package com.portfolio.flashsale.voucher.api;

import com.portfolio.flashsale.auth.security.AuthenticatedUser;
import com.portfolio.flashsale.voucher.dto.VoucherClaimResponse;
import com.portfolio.flashsale.voucher.dto.VoucherResponse;
import com.portfolio.flashsale.voucher.service.VoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<List<VoucherResponse>> listActiveVouchers() {
        return ResponseEntity.ok(voucherService.listActiveVouchers());
    }

    @PostMapping("/{code}/claim")
    @Operation(summary = "Atomically claim a flash voucher if quota remains")
    public ResponseEntity<VoucherClaimResponse> claimVoucher(
            @PathVariable String code,
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(voucherService.claimVoucher(currentUser.id(), code));
    }

    @GetMapping("/my-claims")
    @Operation(summary = "List flash vouchers claimed by the current user")
    public ResponseEntity<List<VoucherClaimResponse>> listMyClaims(
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ResponseEntity.ok(voucherService.listMyClaims(currentUser.id()));
    }
}
