package com.commerce.merchant.dto;

import com.commerce.merchant.domain.MerchantStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class MerchantGetResponse {
    private Long merchantId;
    private String merchantNo;
    private String name;
    private String businessNo;
    private String representativeName;
    private MerchantStatus status;
    private String webhookUrl;
    private OffsetDateTime createdAt;
}