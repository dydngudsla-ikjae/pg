package com.commerce.merchant.exception;

public class ApiKeyNotFoundException extends RuntimeException {
    public ApiKeyNotFoundException(Long keyId) {
        super("API key not found: " + keyId);
    }
}