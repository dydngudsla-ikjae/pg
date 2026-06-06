package com.commerce.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "결제 취소 응답")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentCancelResponse(
        @Schema(description = "결제 고유키", example = "pay_abc123xyz")
        String paymentKey,

        @Schema(description = "취소 후 결제 상태", example = "CANCELLED",
                allowableValues = {"CANCELLED", "PARTIAL_CANCELLED"})
        String status,

        @Schema(description = "원 결제 금액", example = "10000")
        Long amount,

        @Schema(description = "누적 취소 금액", example = "10000")
        Long cancelledAmount,

        @Schema(description = "잔여 금액", example = "0")
        Long remainAmount
) {}