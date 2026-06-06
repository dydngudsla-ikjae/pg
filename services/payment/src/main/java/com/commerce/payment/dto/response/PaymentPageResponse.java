package com.commerce.payment.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "결제 목록 페이징 응답")
public record PaymentPageResponse(
        @Schema(description = "결제 목록")
        List<PaymentResponse> content,

        @Schema(description = "전체 건수", example = "42")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "3")
        int totalPages,

        @Schema(description = "현재 페이지 번호 (0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size
) {}