package com.commerce.merchant.controller;

import com.commerce.merchant.dto.ApiResponse;
import com.commerce.merchant.dto.MerchantRegisterRequest;
import com.commerce.merchant.dto.MerchantRegisterResponse;
import com.commerce.merchant.service.MerchantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MerchantRegisterResponse> register(
            @RequestBody @Valid MerchantRegisterRequest request) {
        return ApiResponse.success(merchantService.register(request));
    }
}