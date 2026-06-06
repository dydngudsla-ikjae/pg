package com.commerce.merchant.controller;

import com.commerce.merchant.dto.CommonResponse;
import com.commerce.merchant.dto.MerchantGetResponse;
import com.commerce.merchant.dto.MerchantRegisterRequest;
import com.commerce.merchant.dto.MerchantRegisterResponse;
import com.commerce.merchant.dto.MerchantStatusUpdateRequest;
import com.commerce.merchant.dto.WebhookUpdateRequest;
import com.commerce.merchant.service.MerchantService;
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

@Tag(name = "가맹점", description = "가맹점 등록, 조회, 상태 및 웹훅 관리 API")
@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @Operation(summary = "가맹점 등록",
            description = "신규 가맹점을 등록합니다. 사업자번호 중복 시 409를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "가맹점 등록 성공",
                    content = @Content(schema = @Schema(implementation = MerchantRegisterResponse.class))),
            @ApiResponse(responseCode = "400", description = "필수 항목 누락 또는 형식 오류"),
            @ApiResponse(responseCode = "409", description = "사업자번호 중복")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResponse<MerchantRegisterResponse> register(
            @RequestBody @Valid MerchantRegisterRequest request) {
        return CommonResponse.success(merchantService.register(request));
    }

    @Operation(summary = "가맹점 조회",
            description = "가맹점 ID로 상세 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = MerchantGetResponse.class))),
            @ApiResponse(responseCode = "404", description = "가맹점 없음")
    })
    @GetMapping("/{id}")
    public CommonResponse<MerchantGetResponse> getMerchant(
            @Parameter(description = "가맹점 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.success(merchantService.getById(id));
    }

    @Operation(summary = "웹훅 URL 수정",
            description = "알림 수신용 웹훅 URL을 변경합니다. http:// 또는 https:// 형식만 허용합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(schema = @Schema(implementation = MerchantGetResponse.class))),
            @ApiResponse(responseCode = "400", description = "URL 형식 오류"),
            @ApiResponse(responseCode = "404", description = "가맹점 없음")
    })
    @PutMapping("/{id}/webhook")
    public CommonResponse<MerchantGetResponse> updateWebhook(
            @Parameter(description = "가맹점 ID", example = "1") @PathVariable Long id,
            @RequestBody @Valid WebhookUpdateRequest request) {
        return CommonResponse.success(merchantService.updateWebhookUrl(id, request.getWebhookUrl()));
    }

    @Operation(summary = "가맹점 상태 변경",
            description = "가맹점 상태를 ACTIVE / SUSPENDED / TERMINATED 중 하나로 변경합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공",
                    content = @Content(schema = @Schema(implementation = MerchantGetResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 상태값"),
            @ApiResponse(responseCode = "404", description = "가맹점 없음")
    })
    @PatchMapping("/{id}/status")
    public CommonResponse<MerchantGetResponse> updateStatus(
            @Parameter(description = "가맹점 ID", example = "1") @PathVariable Long id,
            @RequestBody @Valid MerchantStatusUpdateRequest request) {
        return CommonResponse.success(merchantService.updateStatus(id, request.getStatus()));
    }
}