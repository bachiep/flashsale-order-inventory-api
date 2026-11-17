package com.portfolio.flashsale.voucher.api;

import com.portfolio.flashsale.voucher.dto.CreateVoucherRequest;
import com.portfolio.flashsale.voucher.dto.VoucherResponse;
import com.portfolio.flashsale.voucher.service.VoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/vouchers")
@Tag(name = "Admin Vouchers", description = "Admin management of limited-quota flash voucher campaigns")
public class AdminVoucherController {

    private final VoucherService voucherService;

    public AdminVoucherController(VoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @PostMapping
    @Operation(summary = "Create a flash voucher campaign")
    public ResponseEntity<VoucherResponse> createVoucher(@Valid @RequestBody CreateVoucherRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(voucherService.createVoucher(request));
    }

    @GetMapping
    @Operation(summary = "List all flash voucher campaigns and redemption counts")
    public ResponseEntity<List<VoucherResponse>> listAllVouchers() {
        return ResponseEntity.ok(voucherService.listAllVouchers());
    }
}
