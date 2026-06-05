package com.commerce.merchant.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_no", nullable = false, unique = true, length = 32)
    private String merchantNo;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "business_no", length = 16)
    private String businessNo;

    @Column(name = "representative_name", length = 64)
    private String representativeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantStatus status;

    @Column(name = "settlement_bank", length = 8)
    private String settlementBank;

    @Column(name = "settlement_account", length = 32)
    private String settlementAccount;

    @Column(name = "webhook_url", length = 512)
    private String webhookUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Builder
    public Merchant(String merchantNo, String name, String businessNo,
                    String representativeName, String settlementBank,
                    String settlementAccount) {
        this.merchantNo = merchantNo;
        this.name = name;
        this.businessNo = businessNo;
        this.representativeName = representativeName;
        this.settlementBank = settlementBank;
        this.settlementAccount = settlementAccount;
        this.status = MerchantStatus.ACTIVE;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }
}