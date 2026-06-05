package com.commerce.merchant.exception;

public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(Long id) {
        super("가맹점을 찾을 수 없습니다. id=" + id);
    }
}
