package com.commerce.merchant.repository;

import com.commerce.merchant.domain.MerchantApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantApiKeyRepository extends JpaRepository<MerchantApiKey, Long> {
    Optional<MerchantApiKey> findByKeyHash(String keyHash);
}