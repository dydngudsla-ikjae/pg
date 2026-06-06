package com.commerce.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "웹훅 URL 수정 요청")
@Getter
@NoArgsConstructor
public class WebhookUpdateRequest {

    @Schema(description = "웹훅 수신 URL (http:// 또는 https:// 형식)", example = "https://example.com/webhook")
    @NotBlank
    @Pattern(regexp = "^https?://.*", message = "Invalid URL format")
    private String webhookUrl;
}