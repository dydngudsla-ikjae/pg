package com.commerce.payment.dto.response;

import java.util.List;

public record PaymentPageResponse(
        List<PaymentResponse> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {}
