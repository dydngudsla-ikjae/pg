package com.commerce.payment.exception;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(String paymentKey) {
        super("결제를 찾을 수 없습니다: " + paymentKey);
    }
}