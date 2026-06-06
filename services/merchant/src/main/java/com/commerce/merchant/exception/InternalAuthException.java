package com.commerce.merchant.exception;

public class InternalAuthException extends RuntimeException {
    public InternalAuthException() {
        super("X-Internal-Service header is required");
    }
}