package com.commerce.merchant.controller;

import com.commerce.merchant.dto.ApiKeyVerifyRequest;
import com.commerce.merchant.dto.ApiKeyVerifyResponse;
import com.commerce.merchant.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/api-keys")
@RequiredArgsConstructor
public class InternalApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping("/verify")
    public ApiKeyVerifyResponse verify(
            @RequestHeader(value = "X-Internal-Service", required = false) String internalService,
            @RequestBody ApiKeyVerifyRequest request) {
        if (internalService == null || internalService.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Internal-Service header is required");
        }
        return apiKeyService.verifyKey(request.getApiKey());
    }
}