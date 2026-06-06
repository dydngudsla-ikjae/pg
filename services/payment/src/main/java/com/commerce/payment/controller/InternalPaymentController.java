package com.commerce.payment.controller;

import com.commerce.payment.dto.ApiResponse;
import com.commerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    @PostMapping("/internal/payments/{paymentKey}/verify")
    public ResponseEntity<ApiResponse<Void>> verify(@PathVariable String paymentKey) {
        paymentService.verify(paymentKey);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}