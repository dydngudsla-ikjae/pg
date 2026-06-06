package com.commerce.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "cancel",
        uniqueConstraints = @UniqueConstraint(columnNames = {"merchant_id", "merchant_cancel_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cancel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "cancel_key", nullable = false, unique = true, length = 64)
    private String cancelKey;

    @Column(name = "merchant_cancel_id", length = 64)
    private String merchantCancelId;

    @Column(name = "cancel_amount", nullable = false)
    private Long cancelAmount;

    @Column(name = "remain_amount", nullable = false)
    private Long remainAmount;

    @Column(length = 256)
    private String reason;

    @Column(name = "pg_cancel_tid", length = 128)
    private String pgCancelTid;

    @Column(name = "cancelled_at", nullable = false)
    private OffsetDateTime cancelledAt;

    @Builder
    public Cancel(Long paymentId, Long merchantId, String cancelKey, String merchantCancelId,
                  Long cancelAmount, Long remainAmount, String reason, String pgCancelTid) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.cancelKey = cancelKey;
        this.merchantCancelId = merchantCancelId;
        this.cancelAmount = cancelAmount;
        this.remainAmount = remainAmount;
        this.reason = reason;
        this.pgCancelTid = pgCancelTid;
        this.cancelledAt = OffsetDateTime.now();
    }
}