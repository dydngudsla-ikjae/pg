package com.commerce.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "idempotency_key",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"merchant_id", "http_method", "endpoint", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(nullable = false, length = 256)
    private String endpoint;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "expired_at", nullable = false)
    private OffsetDateTime expiredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Builder
    public IdempotencyKey(String idempotencyKey, Long merchantId, String httpMethod,
                          String endpoint, Long paymentId, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.merchantId = merchantId;
        this.httpMethod = httpMethod;
        this.endpoint = endpoint;
        this.paymentId = paymentId;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.PROCESSING;
        this.expiredAt = OffsetDateTime.now().plusDays(15);
        this.createdAt = OffsetDateTime.now();
    }

    public void complete(Long paymentId, String responseBody) {
        this.paymentId = paymentId;
        this.responseBody = responseBody;
        this.status = IdempotencyStatus.COMPLETED;
    }
}