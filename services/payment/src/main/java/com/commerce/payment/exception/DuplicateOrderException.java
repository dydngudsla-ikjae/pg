package com.commerce.payment.exception;

public class DuplicateOrderException extends RuntimeException {
    public DuplicateOrderException(String orderId) {
        super("이미 존재하는 주문입니다: " + orderId);
    }
}