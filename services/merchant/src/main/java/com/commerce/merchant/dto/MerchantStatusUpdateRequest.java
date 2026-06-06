package com.commerce.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MerchantStatusUpdateRequest {

    @NotBlank
    private String status;
}