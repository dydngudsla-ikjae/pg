package com.commerce.payment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카드 정보")
public record CardRequest(
        @Schema(description = "카드번호 (하이픈 포함)", example = "1234-5678-9012-3456")
        String number,

        @Schema(description = "유효기간 (MM/YY)", example = "12/26")
        String expiry,

        @Schema(description = "생년월일 6자리 또는 사업자번호 10자리", example = "900101")
        String birthOrBizNo,

        @Schema(description = "카드 비밀번호 앞 2자리", example = "12")
        String pwd2digit
) {}