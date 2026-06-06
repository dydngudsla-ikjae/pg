package com.commerce.merchant.controller;

import com.commerce.merchant.dto.ApiKeyVerifyRequest;
import com.commerce.merchant.dto.ApiKeyVerifyResponse;
import com.commerce.merchant.exception.InternalAuthException;
import com.commerce.merchant.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "내부 API 키", description = "API Gateway 전용 API 키 검증 엔드포인트 (X-Internal-Service 헤더 필수)")
@RestController
@RequestMapping("/internal/api-keys")
@RequiredArgsConstructor
public class InternalApiKeyController {

    private final ApiKeyService apiKeyService;

    @Operation(summary = "API 키 검증",
            description = "API Gateway가 요청 인증 시 API 키 유효성을 확인합니다. X-Internal-Service 헤더가 없으면 401을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증 성공",
                    content = @Content(schema = @Schema(implementation = ApiKeyVerifyResponse.class))),
            @ApiResponse(responseCode = "401", description = "X-Internal-Service 헤더 없음"),
            @ApiResponse(responseCode = "404", description = "존재하지 않거나 폐기된 API 키")
    })
    @PostMapping("/verify")
    public ApiKeyVerifyResponse verify(
            @RequestHeader(value = "X-Internal-Service", required = false) String internalService,
            @RequestBody ApiKeyVerifyRequest request) {
        if (internalService == null || internalService.isBlank()) {
            throw new InternalAuthException();
        }
        return apiKeyService.verifyKey(request.getApiKey());
    }
}