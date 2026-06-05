---
name: api-doc
description: Generate or update Swagger (SpringDoc OpenAPI) annotations for Java Spring Boot controllers. Use when asked to write API docs, add Swagger annotations, or document REST endpoints.
allowed-tools: Read, Grep, Glob
---

# Swagger (SpringDoc OpenAPI) 문서 자동 생성 가이드

## 작업 순서
1. 대상 Controller 파일을 읽어 엔드포인트 파악
2. 관련 Request/Response DTO 파일도 함께 읽기
3. 아래 규칙에 따라 어노테이션 추가 또는 보완
4. 기존 어노테이션이 있으면 덮어쓰지 말고 누락된 부분만 보완

## 어노테이션 규칙

### Controller 클래스
```java
@Tag(name = "주문", description = "주문 생성, 조회, 취소 API")
@RestController
public class OrderController { }
```

### 각 엔드포인트
```java
@Operation(
    summary = "주문 생성",
    description = "상품과 수량을 받아 주문을 생성합니다. 재고가 부족하면 400을 반환합니다."
)
@ApiResponses({
    @ApiResponse(responseCode = "201", description = "주문 생성 성공",
        content = @Content(schema = @Schema(implementation = OrderResponse.class))),
    @ApiResponse(responseCode = "400", description = "재고 부족 또는 잘못된 요청",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "인증 실패"),
    @ApiResponse(responseCode = "500", description = "서버 오류")
})
@PostMapping("/orders")
public ResponseEntity<OrderResponse> createOrder(@RequestBody @Valid OrderRequest request) { }
```

### Path Variable / Request Param
```java
@Parameter(description = "주문 ID", example = "1001")
@PathVariable Long orderId;
```

### DTO 필드
```java
public class OrderRequest {
    @Schema(description = "상품 ID", example = "42")
    private Long productId;

    @Schema(description = "주문 수량", example = "3", minimum = "1")
    private int quantity;
}
```

## 규칙
- summary는 15자 이내로 간결하게
- description은 비즈니스 맥락과 주요 예외 상황을 포함
- example 값은 실제로 유효한 값으로 작성
- 인증이 필요한 엔드포인트는 401 응답 반드시 포함
- 에러 응답은 프로젝트의 공통 ErrorResponse 클래스를 사용
- hidden = true는 사용 금지 (문서화 제외가 필요하면 주석으로 이유 명시)

## 주의사항
- 기존 비즈니스 로직 코드는 절대 수정하지 않음
- 어노테이션 import 문 누락 없이 추가할 것
- Controller, DTO 모두 수정이 필요한 경우 함께 처리
