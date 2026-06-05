package com.commerce.merchant;

import com.commerce.merchant.domain.MerchantStatus;
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

    RestClient restClient;

    @BeforeEach
    void setUp() {
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
}