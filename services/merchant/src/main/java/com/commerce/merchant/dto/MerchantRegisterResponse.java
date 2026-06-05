package com.commerce.merchant.dto;

import com.commerce.merchant.domain.MerchantStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class MerchantRegisterResponse {
    private Long merchantId;
    private String merchantNo;
    private String name;
    private MerchantStatus status;
    private OffsetDateTime createdAt;
}