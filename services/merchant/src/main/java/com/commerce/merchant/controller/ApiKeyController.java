package com.commerce.merchant.controller;

import com.commerce.merchant.dto.ApiKeyIssueRequest;
import com.commerce.merchant.dto.ApiKeyIssueResponse;
import com.commerce.merchant.dto.ApiKeyRevokeResponse;
import com.commerce.merchant.dto.ApiResponse;
import com.commerce.merchant.service.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApiKeyIssueResponse> issueApiKey(
            @PathVariable Long id,
            @RequestBody @Valid ApiKeyIssueRequest request) {
        return ApiResponse.success(apiKeyService.issueKey(id, request.getEnv()));
    }

    @DeleteMapping("/{id}/api-keys/{keyId}")
    public ApiResponse<ApiKeyRevokeResponse> revokeApiKey(
            @PathVariable Long id,
            @PathVariable Long keyId) {
        return ApiResponse.success(apiKeyService.revokeKey(id, keyId));
    }
}