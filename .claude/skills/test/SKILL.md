---
name: test
description: Write integration tests for Java Spring Boot using JUnit5 and H2. Use when writing tests, adding test coverage, or asked to test a feature. Classicist style, tests the outermost layer with real Spring context and H2 database.
allowed-tools: Read, Grep, Glob, Bash(./gradlew test:*)
---

# Spring Boot Integration Test Guide

## Core Principles
- No mocks. Test with real Spring Context + H2 DB
- Test via actual HTTP requests/responses from the outermost layer
- Structure: Arrange → Act → Assert
- Verify via response (status, body) or additional verification requests only. No direct Repository queries for verification

## Basic Setup

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeatureTest {

    @LocalServerPort
    private int port;

    private RestClient client;

    @Autowired
    private SomeRepository someRepository; // For test data cleanup only (no verification)

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        someRepository.deleteAll(); // @Transactional doesn't guarantee isolation with RANDOM_PORT
    }
}
```

> **Note**: With `RANDOM_PORT`, the server runs in a separate thread, so `@Transactional` cannot isolate test data. Clean up data directly with Repository in `@BeforeEach`. Repository is allowed **for setup purposes only** — no verification use.

## Test Writing Pattern

```java
@Test
@DisplayName("valid request returns 200")
void validRequestReturns200() {
    // arrange
    var request = new SomeRequest("value");

    // act
    var response = client.post()
        .uri("/api/some-endpoint")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .retrieve()
        .toEntity(SomeResponse.class);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().getField()).isEqualTo("expected");
}

@Test
@DisplayName("created resource is retrievable after creation")
void createdResourceIsRetrievable() {
    // arrange
    var createRequest = new CreateRequest("test");
    client.post()
        .uri("/api/some-endpoint")
        .body(createRequest)
        .retrieve()
        .toBodilessEntity();

    // act (additional verification request)
    var response = client.get()
        .uri("/api/some-endpoint/1")
        .retrieve()
        .toEntity(SomeResponse.class);

    // assert
    assertThat(response.getBody().getName()).isEqualTo("test");
}
```

## Exception Cases

```java
@Test
@DisplayName("invalid request returns 400")
void invalidRequestReturns400() {
    // arrange
    var invalidRequest = new SomeRequest("");

    // act & assert
    assertThatThrownBy(() ->
        client.post()
            .uri("/api/some-endpoint")
            .body(invalidRequest)
            .retrieve()
            .toEntity(ErrorResponse.class)
    ).isInstanceOf(HttpClientErrorException.BadRequest.class);
}
```

## Rules
1. Test method names in English camelCase; use @DisplayName("") for English descriptions
2. Verify via response (status code, response body) or verification GET requests only
3. No direct Repository queries for verification
4. With `RANDOM_PORT`, clean up data with Repository.deleteAll() in `@BeforeEach`. Repository is for setup only, not verification
5. Run `./gradlew test` after writing tests to confirm they pass