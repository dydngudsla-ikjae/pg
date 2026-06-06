package com.commerce.payment.repository;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {
    Optional<Payment> findByPaymentKey(String paymentKey);
    boolean existsByMerchantIdAndOrderId(Long merchantId, String orderId);
}