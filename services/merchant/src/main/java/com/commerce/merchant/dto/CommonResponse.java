package com.commerce.merchant.dto;

import lombok.Getter;

@Getter
public class CommonResponse<T> {
    private final boolean success;
    private final T data;
    private final Object error;

    private CommonResponse(boolean success, T data, Object error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static <T> CommonResponse<T> success(T data) {
        return new CommonResponse<>(true, data, null);
    }

    public static <T> CommonResponse<T> error(String message) {
        return new CommonResponse<>(false, null, message);
    }
}