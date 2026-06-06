package com.commerce.payment.dto.request;

import com.commerce.payment.domain.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "결제 승인 요청")
public record PaymentApproveRequest(
        @Schema(description = "가맹점 주문 ID (가맹점 내 고유)", example = "ORDER-20240601-001")
        @NotBlank String orderId,

        @Schema(description = "결제 금액 (1 이상 10,000,000 이하)", example = "10000")
        @NotNull @Positive @Max(10_000_000) Long amount,

        @Schema(description = "결제 수단", example = "CARD", allowableValues = {"CARD"})
        @NotNull PaymentMethod method,

        @Schema(description = "카드 정보 (method=CARD일 때 필수)")
        CardRequest card,

        @Schema(description = "할부 개월 수 (0=일시불)", example = "0")
        Integer installmentMonths
) {}