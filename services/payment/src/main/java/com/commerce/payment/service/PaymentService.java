package com.commerce.payment.service;

import com.commerce.payment.client.PgClient;
import com.commerce.payment.domain.Cancel;
import com.commerce.payment.domain.IdempotencyKey;
import com.commerce.payment.domain.IdempotencyStatus;
import com.commerce.payment.domain.OutboxEvent;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentMethod;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.request.PaymentApproveRequest;
import com.commerce.payment.dto.request.PaymentCancelRequest;
import com.commerce.payment.dto.response.PaymentApproveResponse;
import com.commerce.payment.dto.response.PaymentCancelResponse;
import com.commerce.payment.dto.response.PaymentPageResponse;
import com.commerce.payment.dto.response.PaymentResponse;
import com.commerce.payment.exception.DuplicateOrderException;
import com.commerce.payment.exception.IdempotencyConflictException;
import com.commerce.payment.exception.InvalidPaymentStateException;
import com.commerce.payment.exception.PaymentNotFoundException;
import com.commerce.payment.repository.CancelRepository;
import com.commerce.payment.repository.IdempotencyKeyRepository;
import com.commerce.payment.repository.OutboxEventRepository;
import com.commerce.payment.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String HTTP_POST = "POST";
    private static final String APPROVE_ENDPOINT = "/v1/payments";
    private static final String EVENT_PAYMENT_PAID = "payment.paid";
    private static final String EVENT_PAYMENT_CANCELLED = "payment.cancelled";
    private static final String PG_STATUS_PAID = "PAID";

    private final PaymentRepository paymentRepository;
    private final CancelRepository cancelRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PgClient pgClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /*
     * approve/cancel/verify는 PG 호출(외부 HTTP, 최대 3초)을 포함한다.
     * PG 호출을 DB 트랜잭션 안에 두면 ① 호출 동안 커넥션을 점유하고,
     * ② "PG는 성공했지만 커밋 직전 실패해 로컬 기록이 통째로 롤백"되는 정합성 문제가 생긴다.
     * 그래서 prepare(커밋) → PG 호출(트랜잭션 밖) → finalize(커밋) 3단계로 분리한다.
     * prepare에서 커밋된 READY 결제 기록은 장애 시 verify로 재조정할 수 있는 근거가 된다.
     */

    public PaymentApproveResponse approve(Long merchantId, String idempotencyKey, PaymentApproveRequest request) {
        if (request.method() == PaymentMethod.CARD && request.card() == null) {
            throw new IllegalArgumentException("CARD 결제는 카드 정보가 필요합니다.");
        }

        String requestHash = computeHash(request);

        Optional<PaymentApproveResponse> cached = inTransaction(() -> findCachedResponse(
                merchantId, HTTP_POST, APPROVE_ENDPOINT, idempotencyKey, requestHash, PaymentApproveResponse.class));
        if (cached.isPresent()) return cached.get();

        var prepared = inTransaction(() -> prepareApproval(merchantId, idempotencyKey, requestHash, request));

        var pgResult = pgClient.approve(request.orderId(), request.amount(), request.card(), prepared.installmentMonths());

        return inTransaction(() -> finalizeApproval(prepared.paymentId(), prepared.idempotencyKeyId(), pgResult));
    }

    private record PreparedApproval(Long paymentId, Long idempotencyKeyId, int installmentMonths) {}

    private PreparedApproval prepareApproval(Long merchantId, String idempotencyKey, String requestHash,
                                             PaymentApproveRequest request) {
        if (paymentRepository.existsByMerchantIdAndOrderId(merchantId, request.orderId())) {
            throw new DuplicateOrderException(request.orderId());
        }

        var idem = saveProcessingIdempotencyKey(merchantId, HTTP_POST, APPROVE_ENDPOINT, idempotencyKey, requestHash);

        int installmentMonths = request.installmentMonths() != null ? request.installmentMonths() : 0;
        var payment = Payment.builder()
                .merchantId(merchantId)
                .orderId(request.orderId())
                .paymentKey(generateKey("pay"))
                .method(request.method())
                .amount(request.amount())
                .installmentMonths(installmentMonths)
                .build();
        paymentRepository.save(payment);

        return new PreparedApproval(payment.getId(), idem.getId(), installmentMonths);
    }

    private PaymentApproveResponse finalizeApproval(Long paymentId, Long idempotencyKeyId,
                                                     PgClient.PgApproveResult pgResult) {
        var payment = getPaymentById(paymentId);

        switch (pgResult) {
            case PgClient.PgApproveResult.Success s ->
                    payment.approve(s.pgTid(), s.cardCompany(), s.cardLast4(), s.maskedCardNumber());
            case PgClient.PgApproveResult.Declined d ->
                    payment.fail(d.errorCode(), d.errorMessage());
            case PgClient.PgApproveResult.Timeout() ->
                    payment.markUnknown();
        }

        var response = toResponse(payment);
        if (payment.getStatus() == PaymentStatus.PAID) {
            saveOutboxEvent(EVENT_PAYMENT_PAID, payment.getPaymentKey(), response);
        }

        completeIdempotencyKey(idempotencyKeyId, payment.getId(), response);
        return response;
    }

    public PaymentCancelResponse cancel(Long merchantId, String paymentKey, String idempotencyKey, PaymentCancelRequest request) {
        String endpoint = cancelEndpoint(paymentKey);
        String requestHash = computeHash(request);

        Optional<PaymentCancelResponse> cached = inTransaction(() -> findCachedResponse(
                merchantId, HTTP_POST, endpoint, idempotencyKey, requestHash, PaymentCancelResponse.class));
        if (cached.isPresent()) return cached.get();

        var prepared = inTransaction(() ->
                prepareCancellation(merchantId, paymentKey, endpoint, idempotencyKey, requestHash, request));

        var pgResult = pgClient.cancel(prepared.pgTid(), request.cancelAmount(), request.reason());
        if (pgResult instanceof PgClient.PgCancelResult.Failure f) {
            // PG 취소 실패는 재시도가 가능해야 하므로 PROCESSING 멱등키를 해제하고 예외로 종료한다.
            inTransaction(() -> idempotencyKeyRepository.deleteById(prepared.idempotencyKeyId()));
            throw new IllegalStateException("PG 취소 실패: " + f.errorMessage());
        }
        String pgCancelTid = ((PgClient.PgCancelResult.Success) pgResult).pgCancelTid();

        return inTransaction(() -> finalizeCancellation(prepared.paymentId(), prepared.idempotencyKeyId(),
                merchantId, request, pgCancelTid));
    }

    private record PreparedCancellation(Long paymentId, Long idempotencyKeyId, String pgTid) {}

    private PreparedCancellation prepareCancellation(Long merchantId, String paymentKey, String endpoint,
                                                      String idempotencyKey, String requestHash,
                                                      PaymentCancelRequest request) {
        var payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentNotFoundException(paymentKey));

        if (request.merchantCancelId() != null &&
                cancelRepository.existsByMerchantIdAndMerchantCancelId(merchantId, request.merchantCancelId())) {
            throw new InvalidPaymentStateException("이미 사용된 merchantCancelId입니다: " + request.merchantCancelId());
        }

        // PG 호출 전 도메인 유효성 검사 — PG 취소 성공 후 도메인 예외 발생으로 인한 상태 불일치 방지
        if (payment.getStatus() != PaymentStatus.PAID && payment.getStatus() != PaymentStatus.PARTIAL_CANCELLED) {
            throw new IllegalStateException(
                    String.format("상태 전이 불가: %s → CANCELLED/PARTIAL_CANCELLED", payment.getStatus()));
        }
        if (payment.exceedsCancelLimit(request.cancelAmount())) {
            throw new IllegalArgumentException("누적 취소 금액이 원금을 초과합니다.");
        }

        var idem = saveProcessingIdempotencyKey(merchantId, HTTP_POST, endpoint, idempotencyKey, requestHash);

        return new PreparedCancellation(payment.getId(), idem.getId(), payment.getPgTid());
    }

    private PaymentCancelResponse finalizeCancellation(Long paymentId, Long idempotencyKeyId, Long merchantId,
                                                        PaymentCancelRequest request, String pgCancelTid) {
        var payment = getPaymentById(paymentId);
        payment.cancel(request.cancelAmount());

        cancelRepository.save(Cancel.builder()
                .paymentId(payment.getId())
                .merchantId(merchantId)
                .cancelKey(generateKey("cancel"))
                .merchantCancelId(request.merchantCancelId())
                .cancelAmount(request.cancelAmount())
                .remainAmount(payment.remainAmount())
                .reason(request.reason())
                .pgCancelTid(pgCancelTid)
                .build());

        var response = toCancelResponse(payment);
        saveOutboxEvent(EVENT_PAYMENT_CANCELLED, payment.getPaymentKey(), response);

        completeIdempotencyKey(idempotencyKeyId, payment.getId(), response);
        return response;
    }

    public void verify(String paymentKey) {
        var payment = inTransaction(() -> paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentNotFoundException(paymentKey)));

        if (payment.getStatus() != PaymentStatus.UNKNOWN && payment.getStatus() != PaymentStatus.READY) {
            return;
        }

        var pgResult = pgClient.verify(payment.getPgTid() != null ? payment.getPgTid() : paymentKey);

        inTransaction(() -> applyVerificationResult(payment.getId(), pgResult));
    }

    private void applyVerificationResult(Long paymentId, PgClient.PgVerifyResult pgResult) {
        var payment = getPaymentById(paymentId);
        if (payment.getStatus() != PaymentStatus.UNKNOWN && payment.getStatus() != PaymentStatus.READY) {
            return;
        }

        if (PG_STATUS_PAID.equals(pgResult.status())) {
            payment.approve(pgResult.pgTid(), pgResult.cardCompany(), pgResult.cardLast4(), pgResult.maskedCardNumber());
            saveOutboxEvent(EVENT_PAYMENT_PAID, payment.getPaymentKey(), toResponse(payment));
        } else {
            payment.fail(pgResult.errorCode(), pgResult.errorMessage());
        }
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
                .orElseThrow(() -> new PaymentNotFoundException(paymentKey));
        if (!payment.getMerchantId().equals(merchantId)) {
            throw new PaymentNotFoundException(paymentKey);
        }
        return toPaymentResponse(payment);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private void inTransaction(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }

    private Payment getPaymentById(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("결제 정보를 찾을 수 없습니다. id=" + paymentId));
    }

    private <T> Optional<T> findCachedResponse(Long merchantId, String httpMethod, String endpoint,
                                                String idempotencyKey, String requestHash, Class<T> responseType) {
        var existing = idempotencyKeyRepository
                .findByMerchantIdAndHttpMethodAndEndpointAndIdempotencyKey(merchantId, httpMethod, endpoint, idempotencyKey);
        if (existing.isEmpty()) return Optional.empty();

        var idem = existing.get();
        if (idem.getStatus() == IdempotencyStatus.PROCESSING) {
            throw new IdempotencyConflictException();
        }
        if (!requestHash.equals(idem.getRequestHash())) {
            throw new InvalidPaymentStateException("동일 멱등키에 다른 요청 본문이 사용되었습니다.");
        }
        try {
            return Optional.of(objectMapper.readValue(idem.getResponseBody(), responseType));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("멱등성 응답 역직렬화 실패", e);
        }
    }

    private IdempotencyKey saveProcessingIdempotencyKey(Long merchantId, String httpMethod, String endpoint,
                                                        String idempotencyKey, String requestHash) {
        var idem = IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .merchantId(merchantId)
                .httpMethod(httpMethod)
                .endpoint(endpoint)
                .requestHash(requestHash)
                .build();
        return idempotencyKeyRepository.save(idem);
    }

    private void completeIdempotencyKey(Long idempotencyKeyId, Long paymentId, Object response) {
        var idem = idempotencyKeyRepository.findById(idempotencyKeyId)
                .orElseThrow(() -> new IllegalStateException("멱등키 정보를 찾을 수 없습니다. id=" + idempotencyKeyId));
        try {
            idem.complete(paymentId, objectMapper.writeValueAsString(response));
            idempotencyKeyRepository.save(idem);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("멱등성 응답 직렬화 실패", e);
        }
    }

    private void saveOutboxEvent(String eventType, String aggregateId, Object payload) {
        try {
            outboxEventRepository.save(OutboxEvent.builder()
                    .aggregateType("payment")
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .build());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("이벤트 직렬화 실패", e);
        }
    }

    private String computeHash(Object obj) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }

    private String generateKey(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String cancelEndpoint(String paymentKey) {
        return "/v1/payments/" + paymentKey + "/cancel";
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

    private PaymentCancelResponse toCancelResponse(Payment payment) {
        return new PaymentCancelResponse(
                payment.getPaymentKey(),
                payment.getStatus().name(),
                payment.getAmount(),
                payment.getCancelledAmount(),
                payment.remainAmount()
        );
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
}