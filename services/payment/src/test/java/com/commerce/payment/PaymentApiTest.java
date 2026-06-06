package com.commerce.payment;

import com.commerce.payment.domain.*;
import com.commerce.payment.repository.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiTest {

    static final Long MERCHANT_ID = 1L;

    static WireMockServer mockPg;

    @LocalServerPort
    private int port;

    private RestClient client;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @Autowired
    private CancelRepository cancelRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeAll
    static void startWireMock() {
        mockPg = new WireMockServer(WireMockConfiguration.wireMockConfig().port(8090));
        mockPg.start();
    }

    @AfterAll
    static void stopWireMock() {
        mockPg.stop();
    }

    @BeforeEach
    void setUp() {
        cancelRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        outboxEventRepository.deleteAll();
        paymentHistoryRepository.deleteAll();
        paymentRepository.deleteAll();

        mockPg.resetAll();
        stubPgApproveSuccess();

        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-Merchant-Id", MERCHANT_ID.toString())
                .build();
    }

    // ===== WireMock stub helpers =====

    private void stubPgApproveSuccess() {
        mockPg.stubFor(post(urlEqualTo("/pg/v1/payments"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "pgTid": "PG_TID_001",
                                  "cardCompany": "신한",
                                  "cardLast4": "3456",
                                  "maskedCardNumber": "1234-56**-****-3456"
                                }
                                """)));
    }

    private void stubPgApproveDecline() {
        mockPg.resetAll();
        mockPg.stubFor(post(urlEqualTo("/pg/v1/payments"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "errorCode": "DECLINED",
                                  "errorMessage": "카드 한도 초과"
                                }
                                """)));
    }

    private void stubPgApproveTimeout() {
        mockPg.resetAll();
        mockPg.stubFor(post(urlEqualTo("/pg/v1/payments"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(4000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "pgTid": "PG_TID_001",
                                  "cardCompany": "신한",
                                  "cardLast4": "3456",
                                  "maskedCardNumber": "1234-56**-****-3456"
                                }
                                """)));
    }

    private void stubPgCancelSuccess(String pgTid) {
        mockPg.stubFor(post(urlEqualTo("/pg/v1/payments/" + pgTid + "/cancel"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "pgCancelTid": "PG_CANCEL_001"
                                }
                                """)));
    }

    private void stubPgVerifyPaid(String pgTid) {
        mockPg.stubFor(get(urlEqualTo("/pg/v1/payments/" + pgTid))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "PAID",
                                  "pgTid": "PG_TID_001",
                                  "cardCompany": "신한",
                                  "cardLast4": "3456",
                                  "maskedCardNumber": "1234-56**-****-3456"
                                }
                                """)));
    }

    private void stubPgVerifyFailed(String pgTid) {
        mockPg.stubFor(get(urlEqualTo("/pg/v1/payments/" + pgTid))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "FAILED",
                                  "pgTid": "%s",
                                  "errorCode": "DECLINED",
                                  "errorMessage": "거절됨"
                                }
                                """.formatted(pgTid))));
    }

    // ===== Helper methods =====

    private String approvalBody(String orderId) {
        return """
                {
                  "orderId": "%s",
                  "amount": 10000,
                  "method": "CARD",
                  "card": {
                    "number": "1234-5678-9012-3456",
                    "expiry": "12/26",
                    "birthOrBizNo": "900101",
                    "pwd2digit": "12"
                  },
                  "installmentMonths": 0
                }
                """.formatted(orderId);
    }

    private String approvePayment(String idempotencyKey) {
        return approvePayment(idempotencyKey, "ORDER_" + idempotencyKey);
    }

    private String approvePayment(String idempotencyKey, String orderId) {
        var response = client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(approvalBody(orderId))
                .retrieve()
                .toEntity(Map.class);

        var data = (Map<?, ?>) response.getBody().get("data");
        return (String) data.get("paymentKey");
    }

    // ===== 결제 승인 (POST /v1/payments) =====

    @Test
    @DisplayName("결제 승인 API가 카드 결제 유효한 요청으로 200과 PAID 상태를 반환한다")
    void approveCardPaymentReturns200WithPaidStatus() {
        var response = client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", "idem-001")
                .contentType(MediaType.APPLICATION_JSON)
                .body(approvalBody("ORDER-001"))
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("결제 승인 API가 paymentKey를 pay_ 접두어 형식으로 생성한다")
    void approveReturnsPaymentKeyWithPayPrefix() {
        var response = client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", "idem-002")
                .contentType(MediaType.APPLICATION_JSON)
                .body(approvalBody("ORDER-002"))
                .retrieve()
                .toEntity(Map.class);

        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat((String) data.get("paymentKey")).startsWith("pay_");
    }

    @Test
    @DisplayName("결제 승인 API가 카드 원문(number, expiry, birthOrBizNo, pwd2digit)을 DB에 저장하지 않는다")
    void approveDoesNotStoreRawCardDataInDb() {
        String paymentKey = approvePayment("idem-003", "ORDER-003");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();

        String rawCardNumber = "1234-5678-9012-3456";
        assertThat(payment.getPgTid()).isNotNull();
        assertThat(payment.getCardCompany()).isNotNull();
        assertThat(payment.getCardLast4()).isNotNull();
        assertThat(payment.getMaskedCardNumber()).isNotNull();

        assertThat(payment.getPgTid()).doesNotContain(rawCardNumber);
        assertThat(payment.getCardCompany()).doesNotContain(rawCardNumber);
        assertThat(payment.getCardLast4()).doesNotContain(rawCardNumber);
        assertThat(payment.getMaskedCardNumber()).doesNotContain(rawCardNumber);
    }

    @Test
    @DisplayName("결제 승인 API가 cardCompany, cardLast4, maskedCardNumber를 DB에 저장한다")
    void approveStoresCardInfoInDb() {
        String paymentKey = approvePayment("idem-004", "ORDER-004");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();

        assertThat(payment.getCardCompany()).isEqualTo("신한");
        assertThat(payment.getCardLast4()).isEqualTo("3456");
        assertThat(payment.getMaskedCardNumber()).isEqualTo("1234-56**-****-3456");
    }

    @Test
    @DisplayName("결제 승인 API가 승인 후 outbox_event(payment.paid)를 PENDING 상태로 저장한다")
    void approveCreatesOutboxEventWithPendingStatus() {
        approvePayment("idem-005", "ORDER-005");

        List<OutboxEvent> events = outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING");

        assertThat(events).isNotEmpty();
        assertThat(events).anyMatch(e -> e.getEventType().equals("payment.paid"));
    }

    @Test
    @DisplayName("결제 승인 API가 금액 1,000만원 요청 시 승인한다")
    void approveAcceptsMaxAmount() {
        String body = """
                {
                  "orderId": "ORDER-006",
                  "amount": 10000000,
                  "method": "CARD",
                  "card": {
                    "number": "1234-5678-9012-3456",
                    "expiry": "12/26",
                    "birthOrBizNo": "900101",
                    "pwd2digit": "12"
                  },
                  "installmentMonths": 0
                }
                """;

        var response = client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", "idem-006")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("결제 승인 API가 금액 1,000만원 초과 요청 시 400을 반환한다")
    void approveRejects_amountOverMax() {
        String body = """
                {
                  "orderId": "ORDER-007",
                  "amount": 10000001,
                  "method": "CARD",
                  "card": {
                    "number": "1234-5678-9012-3456",
                    "expiry": "12/26",
                    "birthOrBizNo": "900101",
                    "pwd2digit": "12"
                  },
                  "installmentMonths": 0
                }
                """;

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .header("Idempotency-Key", "idem-007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 승인 API가 orderId 누락 시 400을 반환한다")
    void approveRejects_missingOrderId() {
        String body = """
                {
                  "amount": 10000,
                  "method": "CARD",
                  "card": {
                    "number": "1234-5678-9012-3456",
                    "expiry": "12/26",
                    "birthOrBizNo": "900101",
                    "pwd2digit": "12"
                  }
                }
                """;

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .header("Idempotency-Key", "idem-008")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 승인 API가 amount 누락 시 400을 반환한다")
    void approveRejects_missingAmount() {
        String body = """
                {
                  "orderId": "ORDER-009",
                  "method": "CARD",
                  "card": {
                    "number": "1234-5678-9012-3456",
                    "expiry": "12/26",
                    "birthOrBizNo": "900101",
                    "pwd2digit": "12"
                  }
                }
                """;

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .header("Idempotency-Key", "idem-009")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 승인 API가 method 누락 시 400을 반환한다")
    void approveRejects_missingMethod() {
        String body = """
                {
                  "orderId": "ORDER-010",
                  "amount": 10000,
                  "card": {
                    "number": "1234-5678-9012-3456",
                    "expiry": "12/26",
                    "birthOrBizNo": "900101",
                    "pwd2digit": "12"
                  }
                }
                """;

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .header("Idempotency-Key", "idem-010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 승인 API가 CARD 방식에서 card 정보 누락 시 400을 반환한다")
    void approveRejects_missingCardInfo() {
        String body = """
                {
                  "orderId": "ORDER-011",
                  "amount": 10000,
                  "method": "CARD"
                }
                """;

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .header("Idempotency-Key", "idem-011")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 승인 API가 Idempotency-Key 헤더 누락 시 400을 반환한다")
    void approveRejects_missingIdempotencyKey() {
        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(approvalBody("ORDER-012"))
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 승인 API가 PG 거절 시 status=FAILED로 저장하고 실패 응답을 반환한다")
    void approveHandlesPgDecline() {
        stubPgApproveDecline();

        var response = client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", "idem-013")
                .contentType(MediaType.APPLICATION_JSON)
                .body(approvalBody("ORDER-013"))
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("결제 승인 API가 PG 타임아웃 시 status=UNKNOWN과 함께 응답한다")
    void approveHandlesPgTimeout() {
        stubPgApproveTimeout();

        var response = client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", "idem-014")
                .contentType(MediaType.APPLICATION_JSON)
                .body(approvalBody("ORDER-014"))
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("결제 승인 API가 동일 가맹점의 orderId 중복 요청 시 실패한다")
    void approveRejects_duplicateOrderId() {
        approvePayment("idem-015a", "ORDER-015");

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .header("Idempotency-Key", "idem-015b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(approvalBody("ORDER-015"))
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.class);
    }

    // ===== 멱등성 =====

    @Test
    @DisplayName("동일 멱등키로 완료된 요청을 재요청하면 캐싱된 동일 응답을 반환한다")
    void sameIdempotencyKeyReturnsIdenticalResponse() {
        var firstResponse = client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", "idem-016")
                .contentType(MediaType.APPLICATION_JSON)
                .body(approvalBody("ORDER-016"))
                .retrieve()
                .toEntity(Map.class);

        var secondResponse = client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", "idem-016")
                .contentType(MediaType.APPLICATION_JSON)
                .body(approvalBody("ORDER-016"))
                .retrieve()
                .toEntity(Map.class);

        var firstData = (Map<?, ?>) firstResponse.getBody().get("data");
        var secondData = (Map<?, ?>) secondResponse.getBody().get("data");
        assertThat(firstData.get("paymentKey")).isEqualTo(secondData.get("paymentKey"));
    }

    @Test
    @DisplayName("동일 멱등키이지만 request body가 다르면 422를 반환한다")
    void differentBodyWithSameIdempotencyKeyReturns422() {
        approvePayment("idem-017", "ORDER-017");

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .header("Idempotency-Key", "idem-017")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(approvalBody("ORDER-017-DIFFERENT"))
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.UnprocessableEntity.class);
    }

    @Test
    @DisplayName("동일 멱등키로 처리 중인 요청이 있으면 409를 반환한다")
    void processingIdempotencyKeyReturns409() {
        IdempotencyKey processing = IdempotencyKey.builder()
                .idempotencyKey("idem-018")
                .merchantId(MERCHANT_ID)
                .httpMethod("POST")
                .endpoint("/v1/payments")
                .requestHash("some-hash")
                .build();
        idempotencyKeyRepository.save(processing);

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments")
                        .header("Idempotency-Key", "idem-018")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(approvalBody("ORDER-018"))
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.Conflict.class);
    }

    @Test
    @DisplayName("결제 승인에 사용한 멱등키로 결제 취소를 요청하면 별개 요청으로 처리된다")
    void cancelWithApproveIdempotencyKeyIsIndependentRequest() {
        String paymentKey = approvePayment("idem-019", "ORDER-019");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        String cancelBody = """
                {
                  "cancelAmount": 10000,
                  "reason": "테스트 취소",
                  "merchantCancelId": "MC-019"
                }
                """;

        var cancelResponse = client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-019")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cancelBody)
                .retrieve()
                .toEntity(Map.class);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) cancelResponse.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("CANCELLED");
    }

    // ===== 결제 조회 (GET /v1/payments/{paymentKey}) =====

    @Test
    @DisplayName("결제 조회 API가 paymentKey로 200과 전체 필드를 반환한다")
    void getPaymentByKeyReturns200WithAllFields() {
        String paymentKey = approvePayment("idem-020", "ORDER-020");

        var response = client.get()
                .uri("/v1/payments/" + paymentKey)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("paymentKey")).isEqualTo(paymentKey);
        assertThat(data.get("status")).isNotNull();
        assertThat(data.get("amount")).isNotNull();
        assertThat(data.get("method")).isNotNull();
    }

    @Test
    @DisplayName("결제 조회 API가 존재하지 않는 paymentKey 요청 시 404를 반환한다")
    void getPaymentByNonExistentKeyReturns404() {
        assertThatThrownBy(() ->
                client.get()
                        .uri("/v1/payments/pay_nonexistent")
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @DisplayName("결제 조회 API가 부분취소된 결제 조회 시 cancelledAmount와 remainAmount를 포함한다")
    void getPartialCancelledPaymentReturnsCancelledAndRemainAmount() {
        String paymentKey = approvePayment("idem-022", "ORDER-022");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        String cancelBody = """
                {
                  "cancelAmount": 3000,
                  "reason": "부분 취소",
                  "merchantCancelId": "MC-022"
                }
                """;

        client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-022")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cancelBody)
                .retrieve()
                .toBodilessEntity();

        var response = client.get()
                .uri("/v1/payments/" + paymentKey)
                .retrieve()
                .toEntity(Map.class);

        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("cancelledAmount")).isNotNull();
        assertThat(data.get("remainAmount")).isNotNull();
    }

    // ===== 결제 목록 조회 (GET /v1/payments) =====

    @Test
    @DisplayName("결제 목록 조회 API가 200과 페이징 정보를 반환한다")
    void listPaymentsReturns200WithPaging() {
        approvePayment("idem-023", "ORDER-023");

        var response = client.get()
                .uri("/v1/payments")
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("content")).isNotNull();
        assertThat(data.get("totalElements")).isNotNull();
    }

    @Test
    @DisplayName("결제 목록 조회 API가 status 필터로 특정 상태의 결제만 반환한다")
    void listPaymentsFiltersByStatus() {
        approvePayment("idem-024a", "ORDER-024a");
        stubPgApproveDecline();
        client.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", "idem-024b")
                .contentType(MediaType.APPLICATION_JSON)
                .body(approvalBody("ORDER-024b"))
                .retrieve()
                .toBodilessEntity();

        var response = client.get()
                .uri("/v1/payments?status=PAID")
                .retrieve()
                .toEntity(Map.class);

        var data = (Map<?, ?>) response.getBody().get("data");
        var content = (List<?>) data.get("content");
        assertThat(content).allMatch(item -> {
            var payment = (Map<?, ?>) item;
            return "PAID".equals(payment.get("status"));
        });
    }

    @Test
    @DisplayName("결제 목록 조회 API가 from/to 날짜 필터를 적용한다")
    void listPaymentsFiltersByDateRange() {
        approvePayment("idem-025", "ORDER-025");

        String from = OffsetDateTime.now().minusDays(1).toLocalDate().toString();
        String to = OffsetDateTime.now().plusDays(1).toLocalDate().toString();

        var response = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/payments")
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build())
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) response.getBody().get("data");
        var content = (List<?>) data.get("content");
        assertThat(content).isNotEmpty();
    }

    @Test
    @DisplayName("결제 목록 조회 API가 다른 가맹점의 결제를 반환하지 않는다")
    void listPaymentsDoesNotReturnOtherMerchantPayments() {
        Payment otherMerchantPayment = Payment.builder()
                .merchantId(999L)
                .orderId("ORDER-026")
                .paymentKey("pay_other_merchant")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        paymentRepository.save(otherMerchantPayment);

        var response = client.get()
                .uri("/v1/payments")
                .retrieve()
                .toEntity(Map.class);

        var data = (Map<?, ?>) response.getBody().get("data");
        var content = (List<?>) data.get("content");
        assertThat(content).noneMatch(item -> {
            var payment = (Map<?, ?>) item;
            return "pay_other_merchant".equals(payment.get("paymentKey"));
        });
    }

    // ===== 결제 취소 (POST /v1/payments/{paymentKey}/cancel) =====

    @Test
    @DisplayName("결제 취소 API가 전액 취소 후 status=CANCELLED와 200을 반환한다")
    void cancelPaymentFullAmountReturnsCANCELLED() {
        String paymentKey = approvePayment("idem-027", "ORDER-027");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        String cancelBody = """
                {
                  "cancelAmount": 10000,
                  "reason": "전액 취소",
                  "merchantCancelId": "MC-027"
                }
                """;

        var response = client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-027")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cancelBody)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("결제 취소 API가 동일 멱등키로 완료된 취소 재요청 시 캐싱된 동일 응답을 반환한다")
    void cancelWithSameIdempotencyKeyReturnsCachedResponse() {
        String paymentKey = approvePayment("idem-028", "ORDER-028");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        String cancelBody = """
                {
                  "cancelAmount": 10000,
                  "reason": "전액 취소",
                  "merchantCancelId": "MC-028"
                }
                """;

        var firstResponse = client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-028")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cancelBody)
                .retrieve()
                .toEntity(Map.class);

        var secondResponse = client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-028")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cancelBody)
                .retrieve()
                .toEntity(Map.class);

        var firstData = (Map<?, ?>) firstResponse.getBody().get("data");
        var secondData = (Map<?, ?>) secondResponse.getBody().get("data");
        assertThat(firstData.get("status")).isEqualTo(secondData.get("status"));
    }

    @Test
    @DisplayName("결제 취소 API가 부분 취소 후 status=PARTIAL_CANCELLED, cancelledAmount, remainAmount를 반환한다")
    void partialCancelReturnsPartialCancelledStatus() {
        String paymentKey = approvePayment("idem-029", "ORDER-029");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        String cancelBody = """
                {
                  "cancelAmount": 3000,
                  "reason": "부분 취소",
                  "merchantCancelId": "MC-029"
                }
                """;

        var response = client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-029")
                .contentType(MediaType.APPLICATION_JSON)
                .body(cancelBody)
                .retrieve()
                .toEntity(Map.class);

        var data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PARTIAL_CANCELLED");
        assertThat(data.get("cancelledAmount")).isNotNull();
        assertThat(data.get("remainAmount")).isNotNull();
    }

    @Test
    @DisplayName("결제 취소 API가 부분취소 여러 번 시 누적 취소액을 합산한다")
    void multipleCancelsAccumulateCancelledAmount() {
        String paymentKey = approvePayment("idem-030", "ORDER-030");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-030a")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "cancelAmount": 3000,
                          "reason": "첫 번째 부분 취소",
                          "merchantCancelId": "MC-030a"
                        }
                        """)
                .retrieve()
                .toBodilessEntity();

        var secondResponse = client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-030b")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "cancelAmount": 4000,
                          "reason": "두 번째 부분 취소",
                          "merchantCancelId": "MC-030b"
                        }
                        """)
                .retrieve()
                .toEntity(Map.class);

        var data = (Map<?, ?>) secondResponse.getBody().get("data");
        Number cancelledAmount = (Number) data.get("cancelledAmount");
        assertThat(cancelledAmount.longValue()).isEqualTo(7000L);
    }

    @Test
    @DisplayName("결제 취소 API가 부분취소로 잔여금액이 0이 되면 CANCELLED로 전이한다")
    void partialCancelToZeroRemainTransitionsToCancelled() {
        String paymentKey = approvePayment("idem-031", "ORDER-031");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-031a")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "cancelAmount": 5000,
                          "reason": "첫 번째 부분 취소",
                          "merchantCancelId": "MC-031a"
                        }
                        """)
                .retrieve()
                .toBodilessEntity();

        var finalResponse = client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-031b")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "cancelAmount": 5000,
                          "reason": "나머지 취소",
                          "merchantCancelId": "MC-031b"
                        }
                        """)
                .retrieve()
                .toEntity(Map.class);

        var data = (Map<?, ?>) finalResponse.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("결제 취소 API가 누적 취소액이 원금 초과 시 400을 반환한다")
    void cancelExceedingOriginalAmountReturns400() {
        String paymentKey = approvePayment("idem-032", "ORDER-032");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments/" + paymentKey + "/cancel")
                        .header("Idempotency-Key", "idem-cancel-032")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "cancelAmount": 20000,
                                  "reason": "초과 취소",
                                  "merchantCancelId": "MC-032"
                                }
                                """)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 취소 API가 CANCELLED 상태 결제 취소 시 400을 반환한다")
    void cancelCancelledPaymentReturns400() {
        Payment cancelledPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-033")
                .paymentKey("pay_cancelled_test")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        cancelledPayment.approve("PG_TID_CANCEL", "신한", "1234", "1234-56**-****-1234");
        cancelledPayment.cancel(10000L);
        paymentRepository.save(cancelledPayment);

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments/pay_cancelled_test/cancel")
                        .header("Idempotency-Key", "idem-cancel-033")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "cancelAmount": 5000,
                                  "reason": "취소 시도",
                                  "merchantCancelId": "MC-033"
                                }
                                """)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 취소 API가 FAILED 상태 결제 취소 시 400을 반환한다")
    void cancelFailedPaymentReturns400() {
        Payment failedPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-034")
                .paymentKey("pay_failed_test")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        failedPayment.fail("DECLINED", "카드 거절");
        paymentRepository.save(failedPayment);

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments/pay_failed_test/cancel")
                        .header("Idempotency-Key", "idem-cancel-034")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "cancelAmount": 5000,
                                  "reason": "취소 시도",
                                  "merchantCancelId": "MC-034"
                                }
                                """)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 취소 API가 READY 상태 결제 취소 시 400을 반환한다")
    void cancelReadyPaymentReturns400() {
        Payment readyPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-035")
                .paymentKey("pay_ready_test")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        paymentRepository.save(readyPayment);

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments/pay_ready_test/cancel")
                        .header("Idempotency-Key", "idem-cancel-035")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "cancelAmount": 5000,
                                  "reason": "취소 시도",
                                  "merchantCancelId": "MC-035"
                                }
                                """)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 취소 API가 cancelAmount 누락 시 400을 반환한다")
    void cancelRejects_missingCancelAmount() {
        String paymentKey = approvePayment("idem-036", "ORDER-036");

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments/" + paymentKey + "/cancel")
                        .header("Idempotency-Key", "idem-cancel-036")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "reason": "취소",
                                  "merchantCancelId": "MC-036"
                                }
                                """)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 취소 API가 Idempotency-Key 헤더 누락 시 400을 반환한다")
    void cancelRejects_missingIdempotencyKey() {
        String paymentKey = approvePayment("idem-037", "ORDER-037");

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments/" + paymentKey + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "cancelAmount": 5000,
                                  "reason": "취소",
                                  "merchantCancelId": "MC-037"
                                }
                                """)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("결제 취소 API가 merchantCancelId 중복 요청 시 422를 반환한다")
    void cancelWithDuplicateMerchantCancelIdReturns422() {
        String paymentKey = approvePayment("idem-038", "ORDER-038");

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-038a")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "cancelAmount": 3000,
                          "reason": "첫 번째 취소",
                          "merchantCancelId": "MC-DUPLICATE"
                        }
                        """)
                .retrieve()
                .toBodilessEntity();

        String paymentKey2 = approvePayment("idem-038b", "ORDER-038b");
        Payment payment2 = paymentRepository.findByPaymentKey(paymentKey2).orElseThrow();
        stubPgCancelSuccess(payment2.getPgTid());

        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments/" + paymentKey2 + "/cancel")
                        .header("Idempotency-Key", "idem-cancel-038b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "cancelAmount": 3000,
                                  "reason": "두 번째 취소 (중복 merchantCancelId)",
                                  "merchantCancelId": "MC-DUPLICATE"
                                }
                                """)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.UnprocessableEntity.class);
    }

    @Test
    @DisplayName("결제 취소 API가 존재하지 않는 paymentKey 요청 시 404를 반환한다")
    void cancelNonExistentPaymentKeyReturns404() {
        assertThatThrownBy(() ->
                client.post()
                        .uri("/v1/payments/pay_nonexistent/cancel")
                        .header("Idempotency-Key", "idem-cancel-039")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "cancelAmount": 5000,
                                  "reason": "취소",
                                  "merchantCancelId": "MC-039"
                                }
                                """)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @DisplayName("결제 취소 API가 취소 후 outbox_event(payment.cancelled)를 PENDING 상태로 저장한다")
    void cancelCreatesOutboxEventWithPendingStatus() {
        String paymentKey = approvePayment("idem-040", "ORDER-040");
        outboxEventRepository.deleteAll();

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        stubPgCancelSuccess(payment.getPgTid());

        client.post()
                .uri("/v1/payments/" + paymentKey + "/cancel")
                .header("Idempotency-Key", "idem-cancel-040")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "cancelAmount": 10000,
                          "reason": "전액 취소",
                          "merchantCancelId": "MC-040"
                        }
                        """)
                .retrieve()
                .toBodilessEntity();

        List<OutboxEvent> events = outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        assertThat(events).isNotEmpty();
        assertThat(events).anyMatch(e -> e.getEventType().equals("payment.cancelled"));
    }

    // ===== 결제 결과 확정 — 내부 (POST /internal/payments/{paymentKey}/verify) =====

    @Test
    @DisplayName("verify API가 X-Internal-Service 헤더 없이 호출 시 401 또는 403을 반환한다")
    void verifyWithoutInternalServiceHeaderReturns401or403() {
        String paymentKey = approvePayment("idem-041", "ORDER-041");

        assertThatThrownBy(() ->
                RestClient.create("http://localhost:" + port)
                        .post()
                        .uri("/internal/payments/" + paymentKey + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toBodilessEntity()
        ).satisfies(ex ->
                assertThat(ex).isInstanceOfAny(
                        HttpClientErrorException.Unauthorized.class,
                        HttpClientErrorException.Forbidden.class
                )
        );
    }

    @Test
    @DisplayName("verify API가 UNKNOWN 상태 결제를 PAID로 확정한다")
    void verifyUnknownPaymentTransitionsToPaid() {
        Payment unknownPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-042")
                .paymentKey("pay_unknown_to_paid")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        unknownPayment.markUnknown();
        paymentRepository.save(unknownPayment);

        stubPgVerifyPaid("pay_unknown_to_paid");

        var response = client.post()
                .uri("/internal/payments/pay_unknown_to_paid/verify")
                .header("X-Internal-Service", "scheduler")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment updated = paymentRepository.findByPaymentKey("pay_unknown_to_paid").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("verify API가 UNKNOWN 상태 결제를 FAILED로 확정한다")
    void verifyUnknownPaymentTransitionsToFailed() {
        Payment unknownPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-043")
                .paymentKey("pay_unknown_to_failed")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        unknownPayment.markUnknown();
        paymentRepository.save(unknownPayment);

        stubPgVerifyFailed("pay_unknown_to_failed");

        var response = client.post()
                .uri("/internal/payments/pay_unknown_to_failed/verify")
                .header("X-Internal-Service", "scheduler")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment updated = paymentRepository.findByPaymentKey("pay_unknown_to_failed").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("verify API가 READY 상태 결제를 PAID로 확정한다")
    void verifyReadyPaymentTransitionsToPaid() {
        Payment readyPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-044")
                .paymentKey("pay_ready_to_paid")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        paymentRepository.save(readyPayment);

        stubPgVerifyPaid("pay_ready_to_paid");

        var response = client.post()
                .uri("/internal/payments/pay_ready_to_paid/verify")
                .header("X-Internal-Service", "scheduler")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment updated = paymentRepository.findByPaymentKey("pay_ready_to_paid").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("verify API가 READY 상태 결제를 FAILED로 확정한다")
    void verifyReadyPaymentTransitionsToFailed() {
        Payment readyPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-045")
                .paymentKey("pay_ready_to_failed")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        paymentRepository.save(readyPayment);

        stubPgVerifyFailed("pay_ready_to_failed");

        var response = client.post()
                .uri("/internal/payments/pay_ready_to_failed/verify")
                .header("X-Internal-Service", "scheduler")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment updated = paymentRepository.findByPaymentKey("pay_ready_to_failed").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("verify API가 UNKNOWN 확정 후 outbox_event를 PENDING 상태로 저장한다")
    void verifyCreatesOutboxEventAfterConfirmation() {
        Payment unknownPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-046")
                .paymentKey("pay_unknown_outbox")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        unknownPayment.markUnknown();
        paymentRepository.save(unknownPayment);

        stubPgVerifyPaid("pay_unknown_outbox");
        outboxEventRepository.deleteAll();

        client.post()
                .uri("/internal/payments/pay_unknown_outbox/verify")
                .header("X-Internal-Service", "scheduler")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toBodilessEntity();

        List<OutboxEvent> events = outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        assertThat(events).isNotEmpty();
    }

    @Test
    @DisplayName("verify API가 PAID 상태 결제에 호출 시 상태 변경 없이 200을 반환한다")
    void verifyAlreadyPaidPaymentReturns200WithNoChange() {
        Payment paidPayment = Payment.builder()
                .merchantId(MERCHANT_ID)
                .orderId("ORDER-047")
                .paymentKey("pay_already_paid")
                .method(PaymentMethod.CARD)
                .amount(10000L)
                .installmentMonths(0)
                .build();
        paidPayment.approve("PG_TID_PAID", "신한", "3456", "1234-56**-****-3456");
        paymentRepository.save(paidPayment);

        var response = client.post()
                .uri("/internal/payments/pay_already_paid/verify")
                .header("X-Internal-Service", "scheduler")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Payment unchanged = paymentRepository.findByPaymentKey("pay_already_paid").orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("verify API가 존재하지 않는 paymentKey 요청 시 404를 반환한다")
    void verifyNonExistentPaymentKeyReturns404() {
        assertThatThrownBy(() ->
                client.post()
                        .uri("/internal/payments/pay_nonexistent/verify")
                        .header("X-Internal-Service", "scheduler")
                        .contentType(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}