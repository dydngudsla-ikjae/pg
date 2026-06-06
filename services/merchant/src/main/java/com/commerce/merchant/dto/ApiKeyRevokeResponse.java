package com.commerce.merchant.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class ApiKeyRevokeResponse {
    private Long keyId;
    private OffsetDateTime revokedAt;
}