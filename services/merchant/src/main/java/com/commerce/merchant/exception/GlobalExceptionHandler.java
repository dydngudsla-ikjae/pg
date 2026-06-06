package com.commerce.merchant.exception;

import com.commerce.merchant.dto.CommonResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MerchantNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public CommonResponse<Void> handleMerchantNotFound(MerchantNotFoundException e) {
        return CommonResponse.error(e.getMessage());
    }

    @ExceptionHandler(ApiKeyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public CommonResponse<Void> handleApiKeyNotFound(ApiKeyNotFoundException e) {
        return CommonResponse.error(e.getMessage());
    }

    @ExceptionHandler(InternalAuthException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public CommonResponse<Void> handleInternalAuth(InternalAuthException e) {
        return CommonResponse.error(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        return CommonResponse.error("VALIDATION_ERROR");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return CommonResponse.error(e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResponse<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return CommonResponse.error("INVALID_REQUEST_BODY");
    }
}