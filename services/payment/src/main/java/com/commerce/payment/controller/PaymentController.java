package com.commerce.payment.controller;

import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.CommonResponse;
import com.commerce.payment.dto.request.PaymentApproveRequest;
import com.commerce.payment.dto.request.PaymentCancelRequest;
import com.commerce.payment.dto.response.PaymentApproveResponse;
import com.commerce.payment.dto.response.PaymentCancelResponse;
import com.commerce.payment.dto.response.PaymentPageResponse;
import com.commerce.payment.dto.response.PaymentResponse;
import com.commerce.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "결제", description = "결제 승인, 단건 조회, 목록 조회, 취소 API. X-Merchant-Id 헤더는 API Gateway가 주입합니다.")
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "결제 승인",
            description = """
                    카드 결제를 승인합니다.
                    PG 거절 시에도 200으로 응답하며 status=FAILED를 반환합니다.
                    PG 응답 지연(타임아웃) 시 status=UNKNOWN을 반환합니다.
                    Idempotency-Key 헤더가 필수이며, 동일 키로 재요청 시 캐싱된 응답을 반환합니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 처리 완료 (status: PAID / FAILED / UNKNOWN)",
                    content = @Content(schema = @Schema(implementation = PaymentApproveResponse.class))),
            @ApiResponse(responseCode = "400", description = "필수 항목 누락 또는 Idempotency-Key 헤더 없음"),
            @ApiResponse(responseCode = "409", description = "처리 중인 멱등 요청 충돌 또는 중복 orderId"),
            @ApiResponse(responseCode = "422", description = "동일 멱등키에 다른 요청 본문 사용")
    })
    @PostMapping("/v1/payments")
    public ResponseEntity<CommonResponse<PaymentApproveResponse>> approve(
            @Parameter(hidden = true) @RequestHeader("X-Merchant-Id") Long merchantId,
            @Parameter(description = "멱등성 키 (UUID 권장)", example = "550e8400-e29b-41d4-a716-446655440000")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentApproveRequest request) {
        return ResponseEntity.ok(CommonResponse.ok(paymentService.approve(merchantId, idempotencyKey, request)));
    }

    @Operation(summary = "결제 목록 조회",
            description = "가맹점의 결제 목록을 페이징 조회합니다. status, from, to 파라미터로 필터링 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = PaymentPageResponse.class)))
    })
    @GetMapping("/v1/payments")
    public ResponseEntity<CommonResponse<PaymentPageResponse>> listPayments(
            @Parameter(hidden = true) @RequestHeader("X-Merchant-Id") Long merchantId,
            @Parameter(description = "결제 상태 필터", example = "PAID") @RequestParam(required = false) PaymentStatus status,
            @Parameter(description = "조회 시작일 (yyyy-MM-dd)", example = "2024-06-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (yyyy-MM-dd)", example = "2024-06-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "페이지 번호 (0부터)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "20") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(CommonResponse.ok(
                paymentService.listPayments(merchantId, status, from, to, page, size)));
    }

    @Operation(summary = "결제 단건 조회",
            description = "paymentKey로 결제 상세 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "404", description = "결제 없음")
    })
    @GetMapping("/v1/payments/{paymentKey}")
    public ResponseEntity<CommonResponse<PaymentResponse>> getPayment(
            @Parameter(hidden = true) @RequestHeader("X-Merchant-Id") Long merchantId,
            @Parameter(description = "결제 고유키", example = "pay_abc123xyz") @PathVariable String paymentKey) {
        return ResponseEntity.ok(CommonResponse.ok(paymentService.getPayment(merchantId, paymentKey)));
    }

    @Operation(summary = "결제 취소",
            description = """
                    결제를 전액 또는 부분 취소합니다.
                    누적 취소액이 원금을 초과할 수 없습니다.
                    Idempotency-Key 헤더가 필수이며, 동일 키로 재요청 시 캐싱된 응답을 반환합니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 처리 완료 (status: CANCELLED / PARTIAL_CANCELLED)",
                    content = @Content(schema = @Schema(implementation = PaymentCancelResponse.class))),
            @ApiResponse(responseCode = "400", description = "취소 불가 상태 또는 취소 금액 초과"),
            @ApiResponse(responseCode = "404", description = "결제 없음"),
            @ApiResponse(responseCode = "409", description = "처리 중인 멱등 요청 충돌"),
            @ApiResponse(responseCode = "422", description = "중복 merchantCancelId 또는 동일 멱등키에 다른 요청 본문")
    })
    @PostMapping("/v1/payments/{paymentKey}/cancel")
    public ResponseEntity<CommonResponse<PaymentCancelResponse>> cancel(
            @Parameter(hidden = true) @RequestHeader("X-Merchant-Id") Long merchantId,
            @Parameter(description = "멱등성 키 (UUID 권장)", example = "550e8400-e29b-41d4-a716-446655440001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Parameter(description = "결제 고유키", example = "pay_abc123xyz") @PathVariable String paymentKey,
            @Valid @RequestBody PaymentCancelRequest request) {
        return ResponseEntity.ok(CommonResponse.ok(paymentService.cancel(merchantId, paymentKey, idempotencyKey, request)));
    }
}