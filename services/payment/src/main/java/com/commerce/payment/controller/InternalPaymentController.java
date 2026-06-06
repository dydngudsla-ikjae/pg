package com.commerce.payment.controller;

import com.commerce.payment.dto.CommonResponse;
import com.commerce.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "내부 결제", description = "스케줄러 등 내부 서비스가 호출하는 결제 확정 API (X-Internal-Service 헤더 필수)")
@RestController
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "결제 결과 확정",
            description = "UNKNOWN / READY 상태 결제를 PG에 조회하여 최종 상태(PAID / FAILED)로 확정합니다. X-Internal-Service 헤더가 없으면 401을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "확정 처리 완료"),
            @ApiResponse(responseCode = "401", description = "X-Internal-Service 헤더 없음"),
            @ApiResponse(responseCode = "404", description = "결제 없음")
    })
    @PostMapping("/internal/payments/{paymentKey}/verify")
    public ResponseEntity<CommonResponse<Void>> verify(
            @Parameter(description = "결제 고유키", example = "pay_abc123xyz") @PathVariable String paymentKey) {
        paymentService.verify(paymentKey);
        return ResponseEntity.ok(CommonResponse.ok(null));
    }
}