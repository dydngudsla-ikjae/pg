package com.commerce.merchant.service;

import com.commerce.merchant.domain.ApiKeyEnv;
import com.commerce.merchant.domain.MerchantApiKey;
import com.commerce.merchant.dto.ApiKeyIssueResponse;
import com.commerce.merchant.dto.ApiKeyRevokeResponse;
import com.commerce.merchant.exception.ApiKeyNotFoundException;
import com.commerce.merchant.exception.MerchantNotFoundException;
import com.commerce.merchant.repository.MerchantApiKeyRepository;
import com.commerce.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final MerchantApiKeyRepository merchantApiKeyRepository;
    private final MerchantRepository merchantRepository;

    @Transactional
    public ApiKeyIssueResponse issueKey(Long merchantId, String envStr) {
        merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        ApiKeyEnv env;
        try {
            env = ApiKeyEnv.valueOf(envStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid env value: " + envStr);
        }
        String prefix = env == ApiKeyEnv.LIVE ? "mk_live_" : "mk_test_";
        String plainKey = prefix + UUID.randomUUID().toString().replace("-", "");

        String keyHash = sha256(plainKey);

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
        MerchantApiKey key = merchantApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ApiKeyNotFoundException(keyId));
        key.revoke();
        return ApiKeyRevokeResponse.builder()
                .keyId(key.getId())
                .revokedAt(key.getRevokedAt())
                .build();
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}