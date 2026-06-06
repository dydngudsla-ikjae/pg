package com.commerce.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Schema(description = "API 키 발급 응답")
@Getter
@Builder
public class ApiKeyIssueResponse {

    @Schema(description = "API 키 DB ID", example = "10")
    private Long keyId;

    @Schema(description = "발급 환경", example = "SANDBOX")
    private String env;

    @Schema(description = "발급된 API 키 평문 (최초 1회만 제공)", example = "sk_test_abc123xyz")
    private String plainKey;

    @Schema(description = "발급일시")
    private OffsetDateTime createdAt;
}