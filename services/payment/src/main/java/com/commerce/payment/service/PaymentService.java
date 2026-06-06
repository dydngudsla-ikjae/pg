package com.commerce.payment.service;

import com.commerce.payment.client.PgClient;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.dto.request.PaymentApproveRequest;
import com.commerce.payment.dto.response.PaymentApproveResponse;
import com.commerce.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;

    public PaymentApproveResponse approve(Long merchantId, String idempotencyKey, PaymentApproveRequest request) {
        String paymentKey = "pay_" + UUID.randomUUID().toString().replace("-", "");
        var payment = Payment.builder()
                .merchantId(merchantId)
                .orderId(request.orderId())
                .paymentKey(paymentKey)
                .method(request.method())
                .amount(request.amount())
                .installmentMonths(request.installmentMonths() != null ? request.installmentMonths() : 0)
                .build();
        paymentRepository.save(payment);

        var pgResult = pgClient.approve(
                request.orderId(), request.amount(), request.card(),
                request.installmentMonths() != null ? request.installmentMonths() : 0);

        switch (pgResult) {
            case PgClient.PgApproveResult.Success s ->
                    payment.approve(s.pgTid(), s.cardCompany(), s.cardLast4(), s.maskedCardNumber());
            case PgClient.PgApproveResult.Declined d ->
                    payment.fail(d.errorCode(), d.errorMessage());
            case PgClient.PgApproveResult.Timeout t ->
                    payment.markUnknown();
        }

        paymentRepository.save(payment);
        return toResponse(payment);
    }

    private PaymentApproveResponse toResponse(Payment payment) {
        return new PaymentApproveResponse(
                payment.getPaymentKey(),
                payment.getStatus().name(),
                payment.getAmount(),
                payment.getMethod().name(),
                payment.getCardCompany(),
                payment.getCardLast4(),
                payment.getMaskedCardNumber(),
                payment.getFailureCode(),
                payment.getFailureMessage(),
                payment.getPaidAt()
        );
    }
}