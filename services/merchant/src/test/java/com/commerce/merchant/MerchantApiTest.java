package com.commerce.merchant;

import com.commerce.merchant.domain.MerchantStatus;
import com.commerce.merchant.repository.MerchantApiKeyRepository;
import com.commerce.merchant.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantApiTest {

    @LocalServerPort
    int port;

    @Autowired
    MerchantRepository merchantRepository;

    @Autowired
    MerchantApiKeyRepository merchantApiKeyRepository;

    RestClient restClient;

    @BeforeEach
    void setUp() {
        merchantApiKeyRepository.deleteAll();
        merchantRepository.deleteAll();
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("가맹점 등록 API가 유효한 요청으로 201을 반환하고 ACTIVE 상태로 생성한다")
    void registerMerchantReturns201WithActiveStatus() {
        // given
        String requestBody = """
                {
                  "name": "마이쇼핑몰",
                  "businessNo": "123-45-67890",
                  "representativeName": "홍길동",
                  "settlementBank": "088",
                  "settlementAccount": "1234567890"
                }
                """;

        // when
        ResponseEntity<Map> response = restClient.post()
                .uri("/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("error")).isNull();

        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("merchantId")).isNotNull();
        assertThat(data.get("merchantNo")).isNotNull();
        assertThat(data.get("name")).isEqualTo("마이쇼핑몰");
        assertThat(data.get("status")).isEqualTo(MerchantStatus.ACTIVE.name());
        assertThat(data.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("가맹점 등록 후 조회 시 등록 데이터가 일치한다")
    void getMerchantAfterRegisterReturnsMatchingData() {
        // given
        String requestBody = """
                {
                  "name": "마이쇼핑몰",
                  "businessNo": "123-45-67890",
                  "representativeName": "홍길동",
                  "settlementBank": "088",
                  "settlementAccount": "1234567890"
                }
                """;

        // 등록
        ResponseEntity<Map> registerResponse = restClient.post()
                .uri("/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toEntity(Map.class);

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> registerBody = registerResponse.getBody();
        assertThat(registerBody).isNotNull();
        Map<?, ?> registerData = (Map<?, ?>) registerBody.get("data");
        assertThat(registerData).isNotNull();

        Number merchantId = (Number) registerData.get("merchantId");
        assertThat(merchantId).isNotNull();

        // when: 등록된 id로 조회
        ResponseEntity<Map> getResponse = restClient.get()
                .uri("/v1/merchants/" + merchantId)
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> getBody = getResponse.getBody();
        assertThat(getBody).isNotNull();
        assertThat(getBody.get("success")).isEqualTo(true);
        assertThat(getBody.get("error")).isNull();

        Map<?, ?> data = (Map<?, ?>) getBody.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("merchantId")).isEqualTo(merchantId);
        assertThat(data.get("name")).isEqualTo("마이쇼핑몰");
        assertThat(data.get("businessNo")).isEqualTo("123-45-67890");
        assertThat(data.get("representativeName")).isEqualTo("홍길동");
        assertThat(data.get("status")).isEqualTo(MerchantStatus.ACTIVE.name());

        String merchantNo = (String) data.get("merchantNo");
        assertThat(merchantNo).isNotNull();
        assertThat(merchantNo).matches("M\\d{8}\\d{3}");

        assertThat(data.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("가맹점 등록 API가 name 누락 시 400을 반환한다")
    void registerMerchantWithoutNameReturns400() {
        // given: name 필드 누락
        String requestBody = """
                {
                  "businessNo": "123-45-67890",
                  "representativeName": "홍길동",
                  "settlementBank": "088",
                  "settlementAccount": "1234567890"
                }
                """;

        // when & then
        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .toEntity(Map.class)
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("가맹점 등록 API가 businessNo 누락 시 400을 반환한다")
    void registerMerchantWithoutBusinessNoReturns400() {
        // given: businessNo 필드 누락
        String requestBody = """
                {
                  "name": "마이쇼핑몰",
                  "representativeName": "홍길동",
                  "settlementBank": "088",
                  "settlementAccount": "1234567890"
                }
                """;

        // when & then
        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .toEntity(Map.class)
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("가맹점 등록 API가 representativeName 누락 시 400을 반환한다")
    void registerMerchantWithoutRepresentativeNameReturns400() {
        // given: representativeName 필드 누락
        String requestBody = """
                {
                  "name": "마이쇼핑몰",
                  "businessNo": "123-45-67890",
                  "settlementBank": "088",
                  "settlementAccount": "1234567890"
                }
                """;

        // when & then
        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .toEntity(Map.class)
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("가맹점 등록 API가 merchantNo를 M+yyyyMMdd+일련번호 형식으로 생성한다")
    void registerMerchantGeneratesMerchantNoWithDateAndSequence() {
        // given
        String requestBody = """
                {
                  "name": "테스트쇼핑몰",
                  "businessNo": "987-65-43210",
                  "representativeName": "김철수",
                  "settlementBank": "004",
                  "settlementAccount": "9876543210"
                }
                """;

        String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // when
        ResponseEntity<Map> response = restClient.post()
                .uri("/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();

        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data).isNotNull();

        String merchantNo = (String) data.get("merchantNo");
        assertThat(merchantNo).isNotNull();
        assertThat(merchantNo).matches("M\\d{8}\\d{3}");
        assertThat(merchantNo).startsWith("M" + todayStr);
    }

    @Test
    @DisplayName("가맹점 조회 API가 모든 필드를 포함하여 200을 반환한다")
    void getMerchantReturns200WithAllFields() {
        // given: 가맹점 등록
        String registerBody = """
                {
                  "name": "테스트몰",
                  "businessNo": "111-22-33333",
                  "representativeName": "이순신",
                  "settlementBank": "004",
                  "settlementAccount": "9999999999"
                }
                """;

        ResponseEntity<Map> registerResponse = restClient.post()
                .uri("/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(registerBody)
                .retrieve()
                .toEntity(Map.class);

        Map<?, ?> registerData = (Map<?, ?>) registerResponse.getBody().get("data");
        Number merchantId = (Number) registerData.get("merchantId");

        // when
        ResponseEntity<Map> response = restClient.get()
                .uri("/v1/merchants/" + merchantId)
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> body = response.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("error")).isNull();

        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data.get("merchantId")).isNotNull();
        assertThat(data.get("merchantNo")).isNotNull();
        assertThat(data.get("name")).isEqualTo("테스트몰");
        assertThat(data.get("businessNo")).isEqualTo("111-22-33333");
        assertThat(data.get("representativeName")).isEqualTo("이순신");
        assertThat(data.get("status")).isEqualTo(MerchantStatus.ACTIVE.name());
        assertThat(data.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("가맹점 조회 API가 존재하지 않는 id로 요청 시 404를 반환한다")
    void getMerchantWithNonExistentIdReturns404() {
        assertThatThrownBy(() ->
                restClient.get()
                        .uri("/v1/merchants/99999")
                        .retrieve()
                        .toEntity(Map.class)
        ).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @DisplayName("API 키 발급 API가 env=live 요청 시 mk_live_ 형식의 원문 키와 함께 201을 반환한다")
    void issueApiKeyWithLiveEnvReturns201WithMkLivePrefix() {
        // given: 가맹점 등록
        Number merchantId = registerMerchant();

        // when
        ResponseEntity<Map> response = restClient.post()
                .uri("/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("env", "live"))
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<?, ?> body = response.getBody();
        assertThat(body.get("success")).isEqualTo(true);

        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data.get("keyId")).isNotNull();
        assertThat((String) data.get("plainKey")).startsWith("mk_live_");
    }

    @Test
    @DisplayName("API 키 발급 API가 env=test 요청 시 mk_test_ 형식의 원문 키와 함께 201을 반환한다")
    void issueApiKeyWithTestEnvReturns201WithMkTestPrefix() {
        // given
        Number merchantId = registerMerchant();

        // when
        ResponseEntity<Map> response = restClient.post()
                .uri("/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("env", "test"))
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("keyId")).isNotNull();
        assertThat((String) data.get("plainKey")).startsWith("mk_test_");
    }

    @Test
    @DisplayName("API 키 발급 API가 동일 가맹점에 여러 키 발급을 허용한다")
    void issueMultipleApiKeysForSameMerchant() {
        // given
        Number merchantId = registerMerchant();

        // when: 두 번 발급
        ResponseEntity<Map> response1 = restClient.post()
                .uri("/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("env", "live"))
                .retrieve()
                .toEntity(Map.class);

        ResponseEntity<Map> response2 = restClient.post()
                .uri("/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("env", "live"))
                .retrieve()
                .toEntity(Map.class);

        // then: 둘 다 201
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<?, ?> data1 = (Map<?, ?>) response1.getBody().get("data");
        Map<?, ?> data2 = (Map<?, ?>) response2.getBody().get("data");
        assertThat(data1.get("keyId")).isNotEqualTo(data2.get("keyId"));
    }

    @Test
    @DisplayName("API 키 발급 후 DB에는 원문 키가 저장되지 않고 key_hash만 저장된다")
    void afterIssuingApiKeyOnlyKeyHashIsStoredInDb() {
        // given
        Number merchantId = registerMerchant();

        // when
        ResponseEntity<Map> response = restClient.post()
                .uri("/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("env", "live"))
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        Number keyId = (Number) data.get("keyId");
        String plainKey = (String) data.get("plainKey");

        // then: DB에서 조회하여 keyHash만 저장되어 있는지 확인
        var saved = merchantApiKeyRepository.findById(keyId.longValue()).orElseThrow();
        assertThat(saved.getKeyHash()).isNotBlank();
        assertThat(saved.getKeyHash()).isNotEqualTo(plainKey);
    }

    @Test
    @DisplayName("API 키 발급 API가 존재하지 않는 가맹점 id로 요청 시 404를 반환한다")
    void issueApiKeyWithNonExistentMerchantReturns404() {
        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/v1/merchants/99999/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("env", "live"))
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @DisplayName("API 키 발급 API가 유효하지 않은 env 값으로 요청 시 400을 반환한다")
    void issueApiKeyWithInvalidEnvReturns400() {
        Number merchantId = registerMerchant();

        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/v1/merchants/" + merchantId + "/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("env", "invalid"))
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("API 키 발급 API가 env 누락 시 400을 반환한다")
    void issueApiKeyWithMissingEnvReturns400() {
        Number merchantId = registerMerchant();

        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/v1/merchants/" + merchantId + "/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of())
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("API 키 폐기 API가 keyId와 revokedAt을 포함하여 200을 반환한다")
    void revokeApiKeyReturns200WithKeyIdAndRevokedAt() {
        // given: 가맹점 등록 후 API 키 발급
        Number merchantId = registerMerchant();
        Number keyId = issueApiKey(merchantId, "live");

        // when
        ResponseEntity<Map> response = restClient.delete()
                .uri("/v1/merchants/" + merchantId + "/api-keys/" + keyId)
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> body = response.getBody();
        assertThat(body.get("success")).isEqualTo(true);

        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data.get("keyId")).isNotNull();
        assertThat(data.get("revokedAt")).isNotNull();
    }

    @Test
    @DisplayName("API 키 폐기 API가 존재하지 않는 keyId로 요청 시 404를 반환한다")
    void revokeApiKeyWithNonExistentKeyIdReturns404() {
        Number merchantId = registerMerchant();

        assertThatThrownBy(() ->
                restClient.delete()
                        .uri("/v1/merchants/" + merchantId + "/api-keys/99999")
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @DisplayName("API 키 폐기 후 해당 키로 검증 API를 호출하면 valid=false, reason=REVOKED를 반환한다")
    void afterRevokingApiKeyVerifyReturnsRevokedReason() {
        // given: 가맹점 등록 후 API 키 한 번 발급
        Number merchantId = registerMerchant();

        ResponseEntity<Map> issueResponse = restClient.post()
                .uri("/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("env", "live"))
                .retrieve()
                .toEntity(Map.class);

        Map<?, ?> issueData = (Map<?, ?>) issueResponse.getBody().get("data");
        String plainKey = (String) issueData.get("plainKey");
        Number keyId = (Number) issueData.get("keyId");

        // 폐기
        restClient.delete()
                .uri("/v1/merchants/" + merchantId + "/api-keys/" + keyId)
                .retrieve()
                .toBodilessEntity();

        // when: 폐기된 키로 검증
        ResponseEntity<Map> verifyResponse = restClient.post()
                .uri("/internal/api-keys/verify")
                .header("X-Internal-Service", "payment-service")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("apiKey", plainKey))
                .retrieve()
                .toEntity(Map.class);

        // then
        Map<?, ?> verifyBody = verifyResponse.getBody();
        assertThat(verifyBody.get("valid")).isEqualTo(false);
        assertThat(verifyBody.get("reason")).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("웹훅 URL 수정 API가 URL을 저장하고 200을 반환한다")
    void updateWebhookUrlReturns200AndSavesUrl() {
        // given
        Number merchantId = registerMerchant();
        String webhookUrl = "https://example.com/webhook";

        // when
        ResponseEntity<Map> response = restClient.put()
                .uri("/v1/merchants/" + merchantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("webhookUrl", webhookUrl))
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET으로 webhookUrl 확인
        ResponseEntity<Map> getResponse = restClient.get()
                .uri("/v1/merchants/" + merchantId)
                .retrieve()
                .toEntity(Map.class);

        Map<?, ?> data = (Map<?, ?>) getResponse.getBody().get("data");
        assertThat(data.get("webhookUrl")).isEqualTo(webhookUrl);
    }

    @Test
    @DisplayName("웹훅 URL 수정 API가 잘못된 URL 형식으로 요청 시 400을 반환한다")
    void updateWebhookUrlWithInvalidUrlReturns400() {
        Number merchantId = registerMerchant();

        assertThatThrownBy(() ->
                restClient.put()
                        .uri("/v1/merchants/" + merchantId + "/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("webhookUrl", "not-a-url"))
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    @DisplayName("웹훅 URL 수정 API가 존재하지 않는 가맹점 id로 요청 시 404를 반환한다")
    void updateWebhookUrlWithNonExistentMerchantReturns404() {
        assertThatThrownBy(() ->
                restClient.put()
                        .uri("/v1/merchants/99999/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("webhookUrl", "https://example.com/hook"))
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @DisplayName("가맹점 상태 변경 API가 SUSPENDED로 변경 후 200을 반환한다")
    void updateMerchantStatusToSuspendedReturns200() {
        // given
        Number merchantId = registerMerchant();

        // when
        ResponseEntity<Map> response = restClient.patch()
                .uri("/v1/merchants/" + merchantId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "SUSPENDED"))
                .retrieve()
                .toEntity(Map.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("success")).isEqualTo(true);
    }

    // 헬퍼 메서드: 가맹점 등록 후 merchantId 반환
    private Number registerMerchant() {
        String body = """
                {
                  "name": "테스트몰",
                  "businessNo": "123-45-67890",
                  "representativeName": "홍길동",
                  "settlementBank": "088",
                  "settlementAccount": "1234567890"
                }
                """;
        ResponseEntity<Map> response = restClient.post()
                .uri("/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (Number) data.get("merchantId");
    }

    // 헬퍼 메서드: API 키 발급 후 keyId 반환
    private Number issueApiKey(Number merchantId, String env) {
        ResponseEntity<Map> response = restClient.post()
                .uri("/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("env", env))
                .retrieve()
                .toEntity(Map.class);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (Number) data.get("keyId");
    }

    // 헬퍼 메서드: API 키 발급 후 plainKey 반환
    private String issueApiKeyGetPlainKey(Number merchantId, String env) {
        ResponseEntity<Map> response = restClient.post()
                .uri("/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("env", env))
                .retrieve()
                .toEntity(Map.class);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (String) data.get("plainKey");
    }
}