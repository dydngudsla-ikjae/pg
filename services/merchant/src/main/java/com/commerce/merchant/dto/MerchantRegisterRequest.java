package com.commerce.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "가맹점 등록 요청")
@Getter
@NoArgsConstructor
public class MerchantRegisterRequest {

    @Schema(description = "가맹점명", example = "테스트마트")
    @NotBlank
    private String name;

    @Schema(description = "사업자번호 (10자리, 하이픈 없음)", example = "1234567890")
    @NotBlank
    private String businessNo;

    @Schema(description = "대표자명", example = "홍길동")
    @NotBlank
    private String representativeName;

    @Schema(description = "정산 은행명", example = "국민은행")
    private String settlementBank;

    @Schema(description = "정산 계좌번호", example = "123456789012")
    private String settlementAccount;
}