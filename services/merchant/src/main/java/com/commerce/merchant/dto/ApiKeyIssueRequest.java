package com.commerce.merchant.dto;

import com.commerce.merchant.domain.ApiKeyEnv;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "API 키 발급 요청")
@Getter
@NoArgsConstructor
public class ApiKeyIssueRequest {

    @Schema(description = "발급 환경", example = "SANDBOX", allowableValues = {"SANDBOX", "PRODUCTION"})
    @NotNull
    private ApiKeyEnv env;
}