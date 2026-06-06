package com.commerce.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "payment",
        uniqueConstraints = @UniqueConstraint(columnNames = {"merchant_id", "order_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    private static final long MAX_AMOUNT = 10_000_000L;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            PaymentStatus.READY, EnumSet.of(PaymentStatus.PAID, PaymentStatus.FAILED, PaymentStatus.UNKNOWN),
            PaymentStatus.UNKNOWN, EnumSet.of(PaymentStatus.PAID, PaymentStatus.FAILED),
            PaymentStatus.PAID, EnumSet.of(PaymentStatus.PARTIAL_CANCELLED, PaymentStatus.CANCELLED),
            PaymentStatus.PARTIAL_CANCELLED, EnumSet.of(PaymentStatus.PARTIAL_CANCELLED, PaymentStatus.CANCELLED)
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "payment_key", nullable = false, unique = true, length = 64)
    private String paymentKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "cancelled_amount", nullable = false)
    private Long cancelledAmount = 0L;

    @Column(name = "pg_tid", length = 128)
    private String pgTid;

    @Column(name = "card_company", length = 32)
    private String cardCompany;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "masked_card_number", length = 20)
    private String maskedCardNumber;

    @Column(name = "installment_months", nullable = false)
    private Integer installmentMonths = 0;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "failure_message", length = 256)
    private String failureMessage;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public Payment(Long merchantId, String orderId, String paymentKey,
                   PaymentMethod method, Long amount, Integer installmentMonths) {
        if (amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("결제 금액은 1,000만원을 초과할 수 없습니다.");
        }
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.paymentKey = paymentKey;
        this.method = method;
        this.amount = amount;
        this.installmentMonths = installmentMonths != null ? installmentMonths : 0;
        this.cancelledAmount = 0L;
        this.status = PaymentStatus.READY;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void approve(String pgTid, String cardCompany, String cardLast4, String maskedCardNumber) {
        validateTransition(PaymentStatus.PAID);
        this.status = PaymentStatus.PAID;
        this.pgTid = pgTid;
        this.cardCompany = cardCompany;
        this.cardLast4 = cardLast4;
        this.maskedCardNumber = maskedCardNumber;
        this.paidAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void fail(String failureCode, String failureMessage) {
        validateTransition(PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markUnknown() {
        validateTransition(PaymentStatus.UNKNOWN);
        this.status = PaymentStatus.UNKNOWN;
        this.updatedAt = OffsetDateTime.now();
    }

    public void cancel(long cancelAmount) {
        long newCancelledAmount = this.cancelledAmount + cancelAmount;
        if (newCancelledAmount > this.amount) {
            throw new IllegalArgumentException("누적 취소 금액이 원금을 초과합니다.");
        }
        PaymentStatus next = newCancelledAmount == this.amount
                ? PaymentStatus.CANCELLED
                : PaymentStatus.PARTIAL_CANCELLED;
        validateTransition(next);
        this.cancelledAmount = newCancelledAmount;
        this.status = next;
        this.updatedAt = OffsetDateTime.now();
    }

    public long remainAmount() {
        return this.amount - this.cancelledAmount;
    }

    private void validateTransition(PaymentStatus next) {
        Set<PaymentStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(this.status, Set.of());
        if (!allowed.contains(next)) {
            throw new IllegalStateException(
                    String.format("상태 전이 불가: %s → %s", this.status, next));
        }
    }
}