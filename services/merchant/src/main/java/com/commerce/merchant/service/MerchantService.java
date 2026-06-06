package com.commerce.merchant.service;

import com.commerce.merchant.domain.Merchant;
import com.commerce.merchant.domain.MerchantStatus;
import com.commerce.merchant.dto.MerchantGetResponse;
import com.commerce.merchant.dto.MerchantRegisterRequest;
import com.commerce.merchant.dto.MerchantRegisterResponse;
import com.commerce.merchant.exception.MerchantNotFoundException;
import com.commerce.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;

    @Transactional
    public MerchantRegisterResponse register(MerchantRegisterRequest request) {
        String merchantNo = generateMerchantNo();

        Merchant merchant = Merchant.builder()
                .merchantNo(merchantNo)
                .name(request.getName())
                .businessNo(request.getBusinessNo())
                .representativeName(request.getRepresentativeName())
                .settlementBank(request.getSettlementBank())
                .settlementAccount(request.getSettlementAccount())
                .build();

        Merchant saved = merchantRepository.save(merchant);

        return MerchantRegisterResponse.builder()
                .merchantId(saved.getId())
                .merchantNo(saved.getMerchantNo())
                .name(saved.getName())
                .status(saved.getStatus())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public MerchantGetResponse getById(Long id) {
        Merchant merchant = merchantRepository.findById(id)
                .orElseThrow(() -> new MerchantNotFoundException(id));

        return MerchantGetResponse.builder()
                .merchantId(merchant.getId())
                .merchantNo(merchant.getMerchantNo())
                .name(merchant.getName())
                .businessNo(merchant.getBusinessNo())
                .representativeName(merchant.getRepresentativeName())
                .status(merchant.getStatus())
                .webhookUrl(merchant.getWebhookUrl())
                .createdAt(merchant.getCreatedAt())
                .build();
    }

    @Transactional
    public MerchantGetResponse updateWebhookUrl(Long id, String webhookUrl) {
        Merchant merchant = merchantRepository.findById(id)
                .orElseThrow(() -> new MerchantNotFoundException(id));
        merchant.updateWebhookUrl(webhookUrl);
        return MerchantGetResponse.builder()
                .merchantId(merchant.getId())
                .merchantNo(merchant.getMerchantNo())
                .name(merchant.getName())
                .businessNo(merchant.getBusinessNo())
                .representativeName(merchant.getRepresentativeName())
                .status(merchant.getStatus())
                .webhookUrl(merchant.getWebhookUrl())
                .createdAt(merchant.getCreatedAt())
                .build();
    }

    @Transactional
    public MerchantGetResponse updateStatus(Long id, String statusStr) {
        Merchant merchant = merchantRepository.findById(id)
                .orElseThrow(() -> new MerchantNotFoundException(id));
        MerchantStatus status;
        try {
            status = MerchantStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status value: " + statusStr);
        }
        merchant.updateStatus(status);
        return MerchantGetResponse.builder()
                .merchantId(merchant.getId())
                .merchantNo(merchant.getMerchantNo())
                .name(merchant.getName())
                .businessNo(merchant.getBusinessNo())
                .representativeName(merchant.getRepresentativeName())
                .status(merchant.getStatus())
                .webhookUrl(merchant.getWebhookUrl())
                .createdAt(merchant.getCreatedAt())
                .build();
    }

    private String generateMerchantNo() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "M" + today;

        Optional<Merchant> last = merchantRepository
                .findTopByMerchantNoStartingWithOrderByMerchantNoDesc(prefix);

        int seq = last.map(m -> {
            String no = m.getMerchantNo();
            String seqStr = no.substring(prefix.length());
            return Integer.parseInt(seqStr) + 1;
        }).orElse(1);

        return prefix + String.format("%03d", seq);
    }
}