package com.commerce.merchant.controller;

import com.commerce.merchant.dto.ApiKeyIssueRequest;
import com.commerce.merchant.dto.ApiKeyIssueResponse;
import com.commerce.merchant.dto.ApiKeyRevokeResponse;
import com.commerce.merchant.dto.ApiResponse;
import com.commerce.merchant.dto.MerchantGetResponse;
import com.commerce.merchant.dto.MerchantRegisterRequest;
import com.commerce.merchant.dto.MerchantRegisterResponse;
import com.commerce.merchant.dto.MerchantStatusUpdateRequest;
import com.commerce.merchant.dto.WebhookUpdateRequest;
import com.commerce.merchant.service.ApiKeyService;
import com.commerce.merchant.service.MerchantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private final ApiKeyService apiKeyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MerchantRegisterResponse> register(
            @RequestBody @Valid MerchantRegisterRequest request) {
        return ApiResponse.success(merchantService.register(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<MerchantGetResponse> getMerchant(@PathVariable Long id) {
        return ApiResponse.success(merchantService.getById(id));
    }

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

    @PutMapping("/{id}/webhook")
    public ApiResponse<MerchantGetResponse> updateWebhook(
            @PathVariable Long id,
            @RequestBody @Valid WebhookUpdateRequest request) {
        return ApiResponse.success(merchantService.updateWebhookUrl(id, request.getWebhookUrl()));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<MerchantGetResponse> updateStatus(
            @PathVariable Long id,
            @RequestBody @Valid MerchantStatusUpdateRequest request) {
        return ApiResponse.success(merchantService.updateStatus(id, request.getStatus()));
    }
}