package com.commerce.merchant.dto;

import com.commerce.merchant.domain.VerifyFailReason;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiKeyVerifyResponse {
    private boolean valid;
    private Long merchantId;
    private String merchantStatus;
    private VerifyFailReason reason;
}