package com.commerce.payment.controller;

import com.commerce.payment.dto.ApiResponse;
import com.commerce.payment.dto.request.PaymentApproveRequest;
import com.commerce.payment.dto.request.PaymentCancelRequest;
import com.commerce.payment.dto.response.PaymentApproveResponse;
import com.commerce.payment.dto.response.PaymentCancelResponse;
import com.commerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/v1/payments")
    public ResponseEntity<ApiResponse<PaymentApproveResponse>> approve(
            @RequestHeader("X-Merchant-Id") Long merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentApproveRequest request) {
        var result = paymentService.approve(merchantId, idempotencyKey, request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/v1/payments/{paymentKey}/cancel")
    public ResponseEntity<ApiResponse<PaymentCancelResponse>> cancel(
            @RequestHeader("X-Merchant-Id") Long merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String paymentKey,
            @Valid @RequestBody PaymentCancelRequest request) {
        var result = paymentService.cancel(merchantId, paymentKey, idempotencyKey, request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}