package com.commerce.merchant.dto;

import com.commerce.merchant.domain.MerchantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Schema(description = "가맹점 상세 응답")
@Getter
@Builder
public class MerchantGetResponse {

    @Schema(description = "가맹점 DB ID", example = "1")
    private Long merchantId;

    @Schema(description = "가맹점 고유번호", example = "MRC-A1B2C3D4")
    private String merchantNo;

    @Schema(description = "가맹점명", example = "테스트마트")
    private String name;

    @Schema(description = "사업자번호", example = "1234567890")
    private String businessNo;

    @Schema(description = "대표자명", example = "홍길동")
    private String representativeName;

    @Schema(description = "가맹점 상태", example = "ACTIVE")
    private MerchantStatus status;

    @Schema(description = "웹훅 수신 URL", example = "https://example.com/webhook")
    private String webhookUrl;

    @Schema(description = "등록일시")
    private OffsetDateTime createdAt;
}