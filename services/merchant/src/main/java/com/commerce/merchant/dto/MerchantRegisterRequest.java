package com.commerce.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MerchantRegisterRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String businessNo;

    @NotBlank
    private String representativeName;

    private String settlementBank;

    private String settlementAccount;
}