package com.commerce.merchant.dto;

import com.commerce.merchant.domain.ApiKeyEnv;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ApiKeyIssueRequest {

    @NotNull
    private ApiKeyEnv env;
}