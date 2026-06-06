package com.commerce.payment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "결제 취소 요청")
public record PaymentCancelRequest(
        @Schema(description = "취소 금액", example = "5000")
        @NotNull @Positive Long cancelAmount,

        @Schema(description = "취소 사유", example = "고객 변심")
        String reason,

        @Schema(description = "가맹점 취소 고유 ID (중복 불가)", example = "CANCEL-20240601-001")
        String merchantCancelId
) {}