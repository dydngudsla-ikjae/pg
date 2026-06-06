package com.commerce.merchant.dto;

import com.commerce.merchant.domain.MerchantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "가맹점 상태 변경 요청")
@Getter
@NoArgsConstructor
public class MerchantStatusUpdateRequest {

    @Schema(description = "변경할 가맹점 상태", example = "SUSPENDED",
            allowableValues = {"ACTIVE", "SUSPENDED", "TERMINATED"})
    @NotNull
    private MerchantStatus status;
}