package com.commerce.payment.service;

import com.commerce.payment.client.PgClient;
import com.commerce.payment.domain.*;
import com.commerce.payment.dto.request.PaymentApproveRequest;
import com.commerce.payment.dto.request.PaymentCancelRequest;
import com.commerce.payment.dto.response.PaymentApproveResponse;
import com.commerce.payment.dto.response.PaymentCancelResponse;
import com.commerce.payment.dto.response.PaymentPageResponse;
import com.commerce.payment.dto.response.PaymentResponse;
import com.commerce.payment.exception.*;
import com.commerce.payment.repository.CancelRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.commerce.payment.repository.IdempotencyKeyRepository;
import com.commerce.payment.repository.OutboxEventRepository;
import com.commerce.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final CancelRepository cancelRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PgClient pgClient;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public PaymentApproveResponse approve(Long merchantId, String idempotencyKey, PaymentApproveRequest request) {
        if (request.method() == PaymentMethod.CARD && request.card() == null) {
            throw new IllegalArgumentException("CARD 결제는 카드 정보가 필요합니다.");
        }

        String endpoint = "/v1/payments";
        String requestHash = computeHash(request);

        var existing = idempotencyKeyRepository
                .findByMerchantIdAndHttpMethodAndEndpointAndIdempotencyKey(
                        merchantId, "POST", endpoint, idempotencyKey);

        if (existing.isPresent()) {
            var idem = existing.get();
            if (idem.getStatus() == IdempotencyStatus.PROCESSING) {
                throw new IdempotencyConflictException();
            }
            if (!requestHash.equals(idem.getRequestHash())) {
                throw new InvalidPaymentStateException("동일 멱등키에 다른 요청 본문이 사용되었습니다.");
            }
            return objectMapper.readValue(idem.getResponseBody(), PaymentApproveResponse.class);
        }

        if (paymentRepository.existsByMerchantIdAndOrderId(merchantId, request.orderId())) {
            throw new DuplicateOrderException("이미 존재하는 주문입니다: " + request.orderId());
        }

        var idem = IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .merchantId(merchantId)
                .httpMethod("POST")
                .endpoint(endpoint)
                .requestHash(requestHash)
                .build();
        idempotencyKeyRepository.save(idem);

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
            saveOutboxEvent("payment.paid", payment.getPaymentKey(), toResponse(payment));
        }

        var response = toResponse(payment);
        idem.complete(payment.getId(), objectMapper.writeValueAsString(response));
        idempotencyKeyRepository.save(idem);

        return response;
    }

    @SneakyThrows
    public PaymentCancelResponse cancel(Long merchantId, String paymentKey, String idempotencyKey, PaymentCancelRequest request) {
        String endpoint = "/v1/payments/" + paymentKey + "/cancel";
        String requestHash = computeHash(request);

        var existing = idempotencyKeyRepository
                .findByMerchantIdAndHttpMethodAndEndpointAndIdempotencyKey(
                        merchantId, "POST", endpoint, idempotencyKey);

        if (existing.isPresent()) {
            var idem = existing.get();
            if (idem.getStatus() == IdempotencyStatus.PROCESSING) {
                throw new IdempotencyConflictException();
            }
            if (!requestHash.equals(idem.getRequestHash())) {
                throw new InvalidPaymentStateException("동일 멱등키에 다른 요청 본문이 사용되었습니다.");
            }
            return objectMapper.readValue(idem.getResponseBody(), PaymentCancelResponse.class);
        }

        var payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentNotFoundException("결제를 찾을 수 없습니다: " + paymentKey));

        if (request.merchantCancelId() != null &&
                cancelRepository.existsByMerchantIdAndMerchantCancelId(merchantId, request.merchantCancelId())) {
            throw new InvalidPaymentStateException("이미 사용된 merchantCancelId입니다: " + request.merchantCancelId());
        }

        var idem = IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .merchantId(merchantId)
                .httpMethod("POST")
                .endpoint(endpoint)
                .requestHash(requestHash)
                .build();
        idempotencyKeyRepository.save(idem);

        payment.cancel(request.cancelAmount());
        paymentRepository.save(payment);

        var pgResult = pgClient.cancel(payment.getPgTid(), request.cancelAmount(), request.reason());
        String pgCancelTid = pgResult instanceof PgClient.PgCancelResult.Success s ? s.pgCancelTid() : null;

        String cancelKey = "cancel_" + UUID.randomUUID().toString().replace("-", "");
        cancelRepository.save(Cancel.builder()
                .paymentId(payment.getId())
                .merchantId(merchantId)
                .cancelKey(cancelKey)
                .merchantCancelId(request.merchantCancelId())
                .cancelAmount(request.cancelAmount())
                .remainAmount(payment.remainAmount())
                .reason(request.reason())
                .pgCancelTid(pgCancelTid)
                .build());

        saveOutboxEvent("payment.cancelled", payment.getPaymentKey(), toCancelResponse(payment));

        var response = toCancelResponse(payment);
        idem.complete(payment.getId(), objectMapper.writeValueAsString(response));
        idempotencyKeyRepository.save(idem);

        return response;
    }

    private PaymentCancelResponse toCancelResponse(Payment payment) {
        return new PaymentCancelResponse(
                payment.getPaymentKey(),
                payment.getStatus().name(),
                payment.getAmount(),
                payment.getCancelledAmount(),
                payment.remainAmount()
        );
    }

    @SneakyThrows
    private void saveOutboxEvent(String eventType, String aggregateId, Object payload) {
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType("payment")
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(objectMapper.writeValueAsString(payload))
                .build());
    }

    @SneakyThrows
    private String computeHash(Object obj) {
        String json = objectMapper.writeValueAsString(obj);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public void verify(String paymentKey) {
        var payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentNotFoundException("결제를 찾을 수 없습니다: " + paymentKey));

        if (payment.getStatus() != PaymentStatus.UNKNOWN && payment.getStatus() != PaymentStatus.READY) {
            return;
        }

        var pgResult = pgClient.verify(payment.getPgTid() != null ? payment.getPgTid() : paymentKey);

        PaymentStatus before = payment.getStatus();
        if ("PAID".equals(pgResult.status())) {
            payment.approve(pgResult.pgTid(), pgResult.cardCompany(), pgResult.cardLast4(), pgResult.maskedCardNumber());
            saveOutboxEvent("payment.paid", payment.getPaymentKey(), toResponse(payment));
        } else {
            payment.fail(pgResult.errorCode(), pgResult.errorMessage());
        }
        paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public PaymentPageResponse listPayments(Long merchantId, PaymentStatus status,
                                            LocalDate from, LocalDate to,
                                            int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Specification<Payment> spec = (root, query, cb) -> cb.equal(root.get("merchantId"), merchantId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            OffsetDateTime fromDt = from.atStartOfDay().atOffset(ZoneOffset.UTC);
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), fromDt));
        }
        if (to != null) {
            OffsetDateTime toDt = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), toDt));
        }

        var result = paymentRepository.findAll(spec, pageable);
        var content = result.getContent().stream().map(this::toPaymentResponse).toList();
        return new PaymentPageResponse(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long merchantId, String paymentKey) {
        var payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentNotFoundException("결제를 찾을 수 없습니다: " + paymentKey));
        return toPaymentResponse(payment);
    }

    private PaymentResponse toPaymentResponse(Payment payment) {
        return new PaymentResponse(
                payment.getPaymentKey(),
                payment.getStatus().name(),
                payment.getAmount(),
                payment.getCancelledAmount(),
                payment.remainAmount(),
                payment.getMethod().name(),
                payment.getCardCompany(),
                payment.getCardLast4(),
                payment.getMaskedCardNumber(),
                payment.getFailureCode(),
                payment.getFailureMessage(),
                payment.getPaidAt(),
                payment.getCreatedAt()
        );
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