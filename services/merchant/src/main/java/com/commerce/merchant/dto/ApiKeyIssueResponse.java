package com.commerce.merchant.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class ApiKeyIssueResponse {
    private Long keyId;
    private String env;
    private String plainKey;
    private OffsetDateTime createdAt;
}