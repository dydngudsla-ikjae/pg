package com.commerce.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentApproveResponse(
        String paymentKey,
        String status,
        Long amount,
        String method,
        String cardCompany,
        String cardLast4,
        String maskedCardNumber,
        String failureCode,
        String failureMessage,
        OffsetDateTime paidAt
) {}