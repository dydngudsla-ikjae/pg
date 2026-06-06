package com.commerce.payment.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("동일한 멱등성 키로 처리 중인 요청이 있습니다.");
    }
}