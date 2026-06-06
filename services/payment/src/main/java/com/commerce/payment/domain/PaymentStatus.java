package com.commerce.payment.domain;

public enum PaymentStatus {
    READY,
    PAID,
    PARTIAL_CANCELLED,
    CANCELLED,
    FAILED,
    UNKNOWN
}