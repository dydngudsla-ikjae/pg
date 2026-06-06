package com.commerce.payment.dto.request;

public record CardRequest(String number, String expiry, String birthOrBizNo, String pwd2digit) {}