package com.commerce.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WebhookUpdateRequest {

    @NotBlank
    @Pattern(regexp = "^https?://.*", message = "Invalid URL format")
    private String webhookUrl;
}