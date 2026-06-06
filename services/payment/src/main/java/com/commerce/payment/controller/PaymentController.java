package com.commerce.payment.controller;

import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.ApiResponse;
import com.commerce.payment.dto.request.PaymentApproveRequest;
import com.commerce.payment.dto.request.PaymentCancelRequest;
import com.commerce.payment.dto.response.PaymentApproveResponse;
import com.commerce.payment.dto.response.PaymentCancelResponse;
import com.commerce.payment.dto.response.PaymentPageResponse;
import com.commerce.payment.dto.response.PaymentResponse;
import com.commerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/v1/payments")
    public ResponseEntity<ApiResponse<PaymentApproveResponse>> approve(
            @RequestHeader("X-Merchant-Id") Long merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentApproveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.approve(merchantId, idempotencyKey, request)));
    }

    @GetMapping("/v1/payments")
    public ResponseEntity<ApiResponse<PaymentPageResponse>> listPayments(
            @RequestHeader("X-Merchant-Id") Long merchantId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                paymentService.listPayments(merchantId, status, from, to, page, size)));
    }

    @GetMapping("/v1/payments/{paymentKey}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @RequestHeader("X-Merchant-Id") Long merchantId,
            @PathVariable String paymentKey) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.getPayment(merchantId, paymentKey)));
    }

    @PostMapping("/v1/payments/{paymentKey}/cancel")
    public ResponseEntity<ApiResponse<PaymentCancelResponse>> cancel(
            @RequestHeader("X-Merchant-Id") Long merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String paymentKey,
            @Valid @RequestBody PaymentCancelRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.cancel(merchantId, paymentKey, idempotencyKey, request)));
    }
}