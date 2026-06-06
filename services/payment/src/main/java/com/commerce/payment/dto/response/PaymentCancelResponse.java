package com.commerce.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentCancelResponse(
        String paymentKey,
        String status,
        Long amount,
        Long cancelledAmount,
        Long remainAmount
) {}