package com.commerce.merchant.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ApiKeyVerifyRequest {
    private String apiKey;
}