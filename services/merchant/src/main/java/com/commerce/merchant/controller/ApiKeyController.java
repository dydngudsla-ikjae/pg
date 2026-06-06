package com.commerce.merchant.controller;

import com.commerce.merchant.dto.ApiKeyIssueRequest;
import com.commerce.merchant.dto.ApiKeyIssueResponse;
import com.commerce.merchant.dto.ApiKeyRevokeResponse;
import com.commerce.merchant.dto.CommonResponse;
import com.commerce.merchant.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "API 키", description = "가맹점 API 키 발급 및 폐기 관리 API")
@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @Operation(summary = "API 키 발급",
            description = "가맹점에 환경(SANDBOX / PRODUCTION)별 API 키를 발급합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "발급 성공",
                    content = @Content(schema = @Schema(implementation = ApiKeyIssueResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 환경값"),
            @ApiResponse(responseCode = "404", description = "가맹점 없음")
    })
    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResponse<ApiKeyIssueResponse> issueApiKey(
            @Parameter(description = "가맹점 ID", example = "1") @PathVariable Long id,
            @RequestBody @Valid ApiKeyIssueRequest request) {
        return CommonResponse.success(apiKeyService.issueKey(id, request.getEnv()));
    }

    @Operation(summary = "API 키 폐기",
            description = "발급된 API 키를 폐기합니다. 폐기된 키는 인증에 사용할 수 없습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "폐기 성공",
                    content = @Content(schema = @Schema(implementation = ApiKeyRevokeResponse.class))),
            @ApiResponse(responseCode = "404", description = "가맹점 또는 API 키 없음")
    })
    @DeleteMapping("/{id}/api-keys/{keyId}")
    public CommonResponse<ApiKeyRevokeResponse> revokeApiKey(
            @Parameter(description = "가맹점 ID", example = "1") @PathVariable Long id,
            @Parameter(description = "API 키 ID", example = "10") @PathVariable Long keyId) {
        return CommonResponse.success(apiKeyService.revokeKey(id, keyId));
    }
}