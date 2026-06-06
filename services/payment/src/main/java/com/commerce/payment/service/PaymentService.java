package com.commerce.payment.service;

import com.commerce.payment.client.PgClient;
import com.commerce.payment.domain.OutboxEvent;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.request.PaymentApproveRequest;
import com.commerce.payment.dto.response.PaymentApproveResponse;
import com.commerce.payment.exception.DuplicateOrderException;
import com.commerce.payment.repository.OutboxEventRepository;
import com.commerce.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PgClient pgClient;
    private final ObjectMapper objectMapper;

    public PaymentApproveResponse approve(Long merchantId, String idempotencyKey, PaymentApproveRequest request) {
        if (request.method() == com.commerce.payment.domain.PaymentMethod.CARD && request.card() == null) {
            throw new IllegalArgumentException("CARD 결제는 카드 정보가 필요합니다.");
        }

        if (paymentRepository.existsByMerchantIdAndOrderId(merchantId, request.orderId())) {
            throw new DuplicateOrderException("이미 존재하는 주문입니다: " + request.orderId());
        }

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

        if (payment.getStatus() == PaymentStatus.PAID) {
            saveOutboxEvent("payment.paid", payment);
        }

        return toResponse(payment);
    }

    @SneakyThrows
    private void saveOutboxEvent(String eventType, Payment payment) {
        String payload = objectMapper.writeValueAsString(toResponse(payment));
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType("payment")
                .aggregateId(payment.getPaymentKey())
                .eventType(eventType)
                .payload(payload)
                .build());
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