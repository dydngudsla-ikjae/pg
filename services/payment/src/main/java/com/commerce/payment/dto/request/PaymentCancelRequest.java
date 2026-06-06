package com.commerce.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentCancelRequest(
        @NotNull @Positive Long cancelAmount,
        String reason,
        String merchantCancelId
) {}