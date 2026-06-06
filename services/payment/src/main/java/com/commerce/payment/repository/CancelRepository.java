package com.commerce.payment.repository;

import com.commerce.payment.domain.Cancel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CancelRepository extends JpaRepository<Cancel, Long> {
    boolean existsByMerchantIdAndMerchantCancelId(Long merchantId, String merchantCancelId);
    Optional<Cancel> findByCancelKey(String cancelKey);
}