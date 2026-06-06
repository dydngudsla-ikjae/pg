package com.commerce.merchant.controller;

import com.commerce.merchant.dto.ApiKeyVerifyRequest;
import com.commerce.merchant.dto.ApiKeyVerifyResponse;
import com.commerce.merchant.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/api-keys")
@RequiredArgsConstructor
public class InternalApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping("/verify")
    public ApiKeyVerifyResponse verify(
            @RequestHeader(value = "X-Internal-Service", required = true) String internalService,
            @RequestBody ApiKeyVerifyRequest request) {
        return apiKeyService.verifyKey(request.getApiKey());
    }
}