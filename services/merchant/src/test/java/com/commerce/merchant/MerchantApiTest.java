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
}