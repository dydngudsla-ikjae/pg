package com.commerce.merchant.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiKeyVerifyResponse {
    private boolean valid;
    private Long merchantId;
    private String merchantStatus;
    private String reason;
}