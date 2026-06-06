package com.commerce.payment.dto.request;

import com.commerce.payment.domain.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentApproveRequest(
        @NotBlank String orderId,
        @NotNull @Positive @Max(10_000_000) Long amount,
        @NotNull PaymentMethod method,
        CardRequest card,
        Integer installmentMonths
) {}