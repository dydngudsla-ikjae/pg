package com.commerce.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "결제 상세 응답")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        @Schema(description = "결제 고유키", example = "pay_abc123xyz")
        String paymentKey,

        @Schema(description = "결제 상태", example = "PAID")
        String status,

        @Schema(description = "결제 금액", example = "10000")
        Long amount,

        @Schema(description = "누적 취소 금액", example = "0")
        Long cancelledAmount,

        @Schema(description = "잔여 금액", example = "10000")
        Long remainAmount,

        @Schema(description = "결제 수단", example = "CARD")
        String method,

        @Schema(description = "카드사", example = "신한")
        String cardCompany,

        @Schema(description = "카드 끝 4자리", example = "3456")
        String cardLast4,

        @Schema(description = "마스킹된 카드번호", example = "1234-56**-****-3456")
        String maskedCardNumber,

        @Schema(description = "실패 코드 (status=FAILED 시)", example = "DECLINED")
        String failureCode,

        @Schema(description = "실패 메시지 (status=FAILED 시)", example = "카드 한도 초과")
        String failureMessage,

        @Schema(description = "결제 완료일시")
        OffsetDateTime paidAt,

        @Schema(description = "결제 생성일시")
        OffsetDateTime createdAt
) {}