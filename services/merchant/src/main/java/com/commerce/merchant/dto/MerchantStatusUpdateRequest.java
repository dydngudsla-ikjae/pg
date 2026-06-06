package com.commerce.merchant.dto;

import com.commerce.merchant.domain.MerchantStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MerchantStatusUpdateRequest {

    @NotNull
    private MerchantStatus status;
}