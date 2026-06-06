package com.commerce.payment.client;

import com.commerce.payment.dto.request.CardRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class PgClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PgClient(@Value("${internal.mock-pg.url}") String pgUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(pgUrl)
                .requestFactory(factory)
                .build();
    }

    public PgApproveResult approve(String orderId, Long amount, CardRequest card, int installmentMonths) {
        try {
            var body = new PgApproveRequest(orderId, amount,
                    card != null ? card.number() : null,
                    card != null ? card.expiry() : null,
                    card != null ? card.birthOrBizNo() : null,
                    card != null ? card.pwd2digit() : null,
                    installmentMonths);
            var response = restClient.post()
                    .uri("/pg/v1/payments")
                    .body(body)
                    .retrieve()
                    .body(PgApproveSuccessBody.class);
            return new PgApproveResult.Success(
                    response.pgTid(), response.cardCompany(), response.cardLast4(), response.maskedCardNumber());
        } catch (HttpClientErrorException e) {
            try {
                var decline = objectMapper.readValue(e.getResponseBodyAsString(), PgDeclineBody.class);
                return new PgApproveResult.Declined(decline.errorCode(), decline.errorMessage());
            } catch (Exception ex) {
                return new PgApproveResult.Declined("UNKNOWN_ERROR", e.getMessage());
            }
        } catch (Exception e) {
            return new PgApproveResult.Timeout();
        }
    }

    public PgCancelResult cancel(String pgTid, Long cancelAmount, String reason) {
        try {
            var body = new PgCancelRequest(cancelAmount, reason);
            var response = restClient.post()
                    .uri("/pg/v1/payments/{pgTid}/cancel", pgTid)
                    .body(body)
                    .retrieve()
                    .body(PgCancelSuccessBody.class);
            return new PgCancelResult.Success(response.pgCancelTid());
        } catch (Exception e) {
            return new PgCancelResult.Failure(e.getMessage());
        }
    }

    public PgVerifyResult verify(String pgTid) {
        var response = restClient.get()
                .uri("/pg/v1/payments/{pgTid}", pgTid)
                .retrieve()
                .body(PgVerifyBody.class);
        return new PgVerifyResult(response.status(), response.pgTid(),
                response.cardCompany(), response.cardLast4(), response.maskedCardNumber(),
                response.errorCode(), response.errorMessage());
    }

    // ===== PG request/response types =====

    record PgApproveRequest(String orderRef, Long amount, String cardNumber, String cardExpiry,
                            String birthOrBizNo, String pwd2digit, Integer installmentMonths) {}

    record PgApproveSuccessBody(String pgTid, String cardCompany, String cardLast4, String maskedCardNumber) {}

    record PgDeclineBody(String errorCode, String errorMessage) {}

    record PgCancelRequest(Long cancelAmount, String reason) {}

    record PgCancelSuccessBody(String pgCancelTid) {}

    record PgVerifyBody(String status, String pgTid, String cardCompany, String cardLast4,
                        String maskedCardNumber, String errorCode, String errorMessage) {}

    // ===== Result types =====

    public sealed interface PgApproveResult permits PgApproveResult.Success, PgApproveResult.Declined, PgApproveResult.Timeout {
        record Success(String pgTid, String cardCompany, String cardLast4, String maskedCardNumber) implements PgApproveResult {}
        record Declined(String errorCode, String errorMessage) implements PgApproveResult {}
        record Timeout() implements PgApproveResult {}
    }

    public sealed interface PgCancelResult permits PgCancelResult.Success, PgCancelResult.Failure {
        record Success(String pgCancelTid) implements PgCancelResult {}
        record Failure(String errorMessage) implements PgCancelResult {}
    }

    public record PgVerifyResult(String status, String pgTid, String cardCompany, String cardLast4,
                                 String maskedCardNumber, String errorCode, String errorMessage) {}
}