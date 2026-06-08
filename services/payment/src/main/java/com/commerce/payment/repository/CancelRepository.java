package com.commerce.payment.repository;

import com.commerce.payment.domain.Cancel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancelRepository extends JpaRepository<Cancel, Long> {
    boolean existsByMerchantIdAndMerchantCancelId(Long merchantId, String merchantCancelId);
}