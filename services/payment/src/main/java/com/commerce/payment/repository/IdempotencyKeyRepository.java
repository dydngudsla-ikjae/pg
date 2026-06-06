package com.commerce.payment.repository;

import com.commerce.payment.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByMerchantIdAndHttpMethodAndEndpointAndIdempotencyKey(
            Long merchantId, String httpMethod, String endpoint, String idempotencyKey);
}