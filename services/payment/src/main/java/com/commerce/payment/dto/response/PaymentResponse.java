package com.commerce.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        String paymentKey,
        String status,
        Long amount,
        Long cancelledAmount,
        Long remainAmount,
        String method,
        String cardCompany,
        String cardLast4,
        String maskedCardNumber,
        String failureCode,
        String failureMessage,
        OffsetDateTime paidAt,
        OffsetDateTime createdAt
) {}