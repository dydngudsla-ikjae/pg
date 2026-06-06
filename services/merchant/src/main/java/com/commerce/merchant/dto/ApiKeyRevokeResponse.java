package com.commerce.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Schema(description = "API 키 폐기 응답")
@Getter
@Builder
public class ApiKeyRevokeResponse {

    @Schema(description = "폐기된 API 키 ID", example = "10")
    private Long keyId;

    @Schema(description = "폐기일시")
    private OffsetDateTime revokedAt;
}