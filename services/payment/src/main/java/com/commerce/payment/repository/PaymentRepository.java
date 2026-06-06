package com.commerce.payment.repository;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPaymentKey(String paymentKey);
    boolean existsByMerchantIdAndOrderId(Long merchantId, String orderId);
    Page<Payment> findByMerchantId(Long merchantId, Pageable pageable);
    Page<Payment> findByMerchantIdAndStatus(Long merchantId, PaymentStatus status, Pageable pageable);
    Page<Payment> findByMerchantIdAndCreatedAtBetween(Long merchantId, OffsetDateTime from, OffsetDateTime to, Pageable pageable);
}