package com.commerce.payment.domain;

public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}