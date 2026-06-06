package com.commerce.merchant.service;

import com.commerce.merchant.domain.ApiKeyEnv;
import com.commerce.merchant.domain.Merchant;
import com.commerce.merchant.domain.MerchantApiKey;
import com.commerce.merchant.domain.VerifyFailReason;
import com.commerce.merchant.dto.ApiKeyIssueResponse;
import com.commerce.merchant.dto.ApiKeyRevokeResponse;
import com.commerce.merchant.dto.ApiKeyVerifyResponse;
import com.commerce.merchant.exception.ApiKeyNotFoundException;
import com.commerce.merchant.exception.MerchantNotFoundException;
import com.commerce.merchant.repository.MerchantApiKeyRepository;
import com.commerce.merchant.repository.MerchantRepository;
import com.commerce.merchant.util.ApiKeyHashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final MerchantApiKeyRepository merchantApiKeyRepository;
    private final MerchantRepository merchantRepository;

    @Transactional
    public ApiKeyIssueResponse issueKey(Long merchantId, ApiKeyEnv env) {
        merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        String prefix = env == ApiKeyEnv.LIVE ? "mk_live_" : "mk_test_";
        String plainKey = prefix + UUID.randomUUID().toString().replace("-", "");
        String keyHash = ApiKeyHashUtils.sha256(plainKey);

        MerchantApiKey apiKey = MerchantApiKey.builder()
                .merchantId(merchantId)
                .env(env)
                .keyHash(keyHash)
                .build();

        MerchantApiKey saved = merchantApiKeyRepository.save(apiKey);

        return ApiKeyIssueResponse.builder()
                .keyId(saved.getId())
                .env(saved.getEnv().name())
                .plainKey(plainKey)
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional
    public ApiKeyRevokeResponse revokeKey(Long merchantId, Long keyId) {
        MerchantApiKey key = merchantApiKeyRepository.findByIdAndMerchantId(keyId, merchantId)
                .orElseThrow(() -> new ApiKeyNotFoundException(keyId));
        key.revoke();
        return ApiKeyRevokeResponse.builder()
                .keyId(key.getId())
                .revokedAt(key.getRevokedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public ApiKeyVerifyResponse verifyKey(String apiKey) {
        String keyHash = ApiKeyHashUtils.sha256(apiKey);

        return merchantApiKeyRepository.findByKeyHash(keyHash)
                .map(key -> {
                    if (key.isRevoked()) {
                        return ApiKeyVerifyResponse.builder()
                                .valid(false)
                                .reason(VerifyFailReason.REVOKED)
                                .build();
                    }
                    Merchant merchant = merchantRepository.findById(key.getMerchantId())
                            .orElseThrow(() -> new MerchantNotFoundException(key.getMerchantId()));
                    return ApiKeyVerifyResponse.builder()
                            .valid(true)
                            .merchantId(merchant.getId())
                            .merchantStatus(merchant.getStatus().name())
                            .build();
                })
                .orElse(ApiKeyVerifyResponse.builder()
                        .valid(false)
                        .reason(VerifyFailReason.NOT_FOUND)
                        .build());
    }
}