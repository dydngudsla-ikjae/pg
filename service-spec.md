# 결제 플랫폼 — 시스템 구조 & 정책

> Java 17 + Spring Boot 3 기반 MSA 결제 플랫폼
> 결제(승인/취소) · 가상계좌 · 정기결제 · 정산 · 알림 도메인
>
> 관련 문서: [API 명세](./api-spec.md) · [프로세스 & 메시지 설계](./service-process.md)

---

## 1. 시스템 개요

쇼핑몰(가맹점)과 카드사/은행 사이를 중개하는 PG(Payment Gateway) 플랫폼이다.
가맹점은 본 플랫폼의 API 하나만 연동하면 카드·가상계좌·정기결제를 모두 사용할 수 있다.

실제 카드사 연동은 불가능하므로 `mock-pg` 서비스가 PG/은행 역할을 시뮬레이션한다.

## 2. 서비스 구성

| 서비스 | 역할 | 핵심 기술 | DB |
|---|---|---|---|
| api-gateway | 인증, Rate Limiting, 라우팅 | Spring Cloud Gateway | - |
| payment-service | 결제 승인/취소/조회 | 멱등성, 상태머신 | payment_db |
| merchant-service | 가맹점 등록, API 키 관리 | 인증 | merchant_db |
| virtual-account-service | 가상계좌 발급, 입금 처리 | 비동기, Kafka Consumer | va_db |
| billing-service | 빌링키 발급, 자동 청구 | 스케줄러 | billing_db |
| settlement-service | 일별 정산 배치 | Spring Batch | settlement_db |
| notification-service | 웹훅 전송, 재시도 | Kafka Consumer | notification_db |
| mock-pg | PG/은행 시뮬레이터 | 테스트용 | - |

## 3. 아키텍처 원칙

| 원칙 | 내용 |
|---|---|
| DB per Service | 각 서비스는 자신의 DB만 소유. 서비스 간 데이터는 Kafka 이벤트 또는 동기 API로 교환하며, 다른 서비스 DB에 직접 쓰지 않는다 |
| 결제 단일 소유자 | **모든 결제(payment)의 생성·상태 전이는 payment-service만 수행한다.** 가상계좌·정기결제도 결제 레코드는 payment-service가 소유하고, 도메인 서비스는 부가 정보(계좌, 빌링키)만 책임진다 |
| 동기 / 비동기 분리 | 사용자가 즉시 받아야 하는 결과는 동기, 후속 처리(정산·알림)는 Kafka |
| 장애 격리 | 알림·정산 서비스 장애가 결제 성공 흐름을 되돌리지 않음 |
| 이벤트 멱등성 | 모든 Kafka 컨슈머는 eventId 기준 중복 처리 방지 |
| 이벤트 스키마 재사용 | 일반결제·정기결제·가상계좌 입금이 모두 payment.paid 이벤트로 합류 |

### 상태 정의

상태값은 도메인별로 분리한다. (가상계좌의 입금 상태와 결제 상태를 혼용하지 않는다)

**PaymentStatus** (payment-service 소유)

| 상태 | 의미 |
|---|---|
| READY | 결제 생성됨, 승인 전 |
| PAID | 승인 완료 |
| PARTIAL_CANCELLED | 부분 취소됨 |
| CANCELLED | 전액 취소됨 |
| FAILED | 승인 실패 |
| UNKNOWN | PG 타임아웃 등으로 결과 불명 (verify로 확정 필요) |

**VirtualAccountStatus** (virtual-account-service 소유)

| 상태 | 의미 |
|---|---|
| WAITING_DEPOSIT | 계좌 발급됨, 입금 대기 |
| DEPOSITED | 입금 완료 (→ payment-service가 결제 PAID 전이) |
| EXPIRED | 입금 기한 초과 |

> 가상계좌 결제는 두 상태가 한 쌍으로 움직인다. 예) 입금 대기 중이면 Payment=READY, VirtualAccount=WAITING_DEPOSIT.

---

## 4. 서비스별 정책

### payment-service (결제)

| 항목 | 정책 |
|---|---|
| 멱등성 | `Idempotency-Key` 헤더 필수. 동일 키 재요청 시 캐싱된 응답 반환. 동일 키 + 다른 요청 body는 400 반환. 키는 가맹점 범위에서 유일하다: `unique(merchant_id, idempotency_key)`. 키는 생성 후 72시간 보관 후 만료 |
| PG 타임아웃 | Mock PG 호출 3초. 초과 시 결제를 `UNKNOWN`으로 두고, verify API(상태조회)로 결과를 확정한다 |
| 재시도 | PG 호출 실패 시 최대 2회 (지수 백오프 1s→2s). **단, 타임아웃은 재시도 안 함** (중복 승인 위험) |
| 결제 한도 | 건당 최대 1,000만원. 초과 시 거절 |
| 부분취소 | 잔여 금액 내에서 N회 가능. 누적 취소액이 원금 초과 불가 |
| 상태 전이 | 아래 허용 전이 외 차단:<br>`READY → PAID` / `READY → FAILED` / `READY → UNKNOWN`<br>`UNKNOWN → PAID` / `UNKNOWN → FAILED`<br>`PAID → PARTIAL_CANCELLED` / `PAID → CANCELLED`<br>`PARTIAL_CANCELLED → PARTIAL_CANCELLED` / `PARTIAL_CANCELLED → CANCELLED` |
| 카드정보 저장 제한 | 카드 원문(번호·유효기간·`birthOrBizNo`·`pwd2digit`)은 Mock PG 호출에만 사용하고 **DB에 저장하지 않는다**. 저장 가능 값은 `cardCompany`, `cardLast4`, `maskedCardNumber`, `pgTid`로 제한 |
| 가상계좌 결제 소유 | 가상계좌 결제도 결제 레코드는 payment-service가 생성·소유. virtual-account-service는 계좌 발급/입금 콜백만 책임 |

### merchant-service (가맹점)

| 항목 | 정책 |
|---|---|
| 인증 | API Key 발급. 결제 요청 시 Gateway에서 검증 |
| 가맹점 상태 | `ACTIVE` / `SUSPENDED`. 정지 가맹점은 결제 거부 |
| 웹훅 URL | 가맹점당 1개 등록. 결제 결과 통지 대상 |

### virtual-account-service (가상계좌)

| 항목 | 정책 |
|---|---|
| 채번 | 발급 시 가상계좌번호 생성 (상태 `WAITING_DEPOSIT`), 입금 기한 설정 (기본 24h) |
| 입금 확인 | Mock 은행이 입금 콜백 전송 → 금액 일치 검증 → `DEPOSITED` 전이 |
| 만료 | 기한 내 미입금 시 `EXPIRED` 처리 (스케줄러) → `va.expired` 이벤트 발행 → payment-service가 결제 FAILED 전이 |
| 멱등성 | 동일 입금 콜백 중복 수신 시 1회만 처리 (`bankTxId` 기준) |

### billing-service (정기결제)

| 항목 | 정책 |
|---|---|
| 빌링키 | 카드 등록 시 발급. 실제 카드번호 미저장 (암호화 토큰만 보관) |
| 결제 호출 | 자동청구 시 payment-service의 `POST /v1/payments`를 `method: BILLING` + `billingKey`로 호출 (결제 레코드는 payment-service가 소유) |
| 자동청구 | 매일 스케줄러가 당일 청구 대상 조회 후 결제 |
| 청구 실패 | 최대 3일간 재시도. 3회 실패 시 구독 `SUSPENDED` |

### settlement-service (정산)

| 항목 | 정책 |
|---|---|
| 정산 주기 | 일배치. 정산 기준일에 승인된 결제 건을 집계 |
| 집계 대상 | ① 기준일에 승인된 `payment.paid` ② 기준일 이전 발생·미반영된 `payment.cancelled` (차감) ③ 정산 완료 후 취소 건은 다음 정산일에 negative item(ADJUSTMENT)으로 반영 |
| 수수료 | 결제액의 2.5% (고정, 단순화) |
| 정산 항목 | settlement(헤더) + settlement_items(PAYMENT/CANCEL/ADJUSTMENT) 구조로 저장 |

### notification-service (알림)

| 항목 | 정책 |
|---|---|
| 이벤트 소비 | `payment.paid` / `payment.cancelled`만 소비한다. `va.deposited`는 소비하지 않는다 (가상계좌 입금도 결국 payment-service가 `payment.paid`로 재발행하므로, 직접 소비하면 알림이 중복됨) |
| 웹훅 전송 | 이벤트 수신 시 webhook_deliveries 레코드 생성 후 가맹점 URL로 POST |
| 재시도 | 실패 시 최대 5회, 지수 백오프 (1m → 3m → 5m → 10m → 20m) |
| 서명 | 본문 HMAC 서명 헤더 첨부 (위변조 방지) |

---

## 5. 공통 규약

### 인증
- 가맹점 API 호출 시 `X-API-KEY` 헤더 필수
- Gateway에서 검증 후 다운스트림 서비스로 `X-Merchant-Id` 전달

### 내부 서비스 인증
- 서비스 간 내부 호출(billing-service → payment-service, payment-service → virtual-account-service 등)은 가맹점 API Key를 사용하지 않는다.
- 내부 호출은 Gateway를 거치지 않는 내부망 통신이며, 다음 헤더로 식별한다:
  - `X-Internal-Service`: 호출 서비스명 (예: `billing-service`)
  - `X-Merchant-Id`: 결제 대상 가맹점 ID
- 즉, 정기결제 자동청구 시 billing-service는 **가맹점 API Key를 보관하지 않고** 내부 인증 헤더로 payment-service를 호출한다.
- **신뢰 경계**: `X-Internal-Service` / `X-Merchant-Id`는 내부망에서만 유효하다. 외부 요청에 이 헤더가 포함되어 들어오면 **Gateway에서 제거하거나 거부**한다 (헤더 위조를 통한 인증 우회 방지).

### 멱등성
- 상태를 변경하는 요청(결제, 취소)은 `Idempotency-Key` 헤더 사용
- 키 + 요청 해시를 저장하여 중복/변조 요청 차단
- 키는 가맹점 범위에서 유일하다: `unique(merchant_id, idempotency_key)` — 서로 다른 가맹점이 같은 키를 써도 충돌하지 않음
- 키는 생성 후 **72시간** 보관 후 만료 (그 이후 동일 키는 신규 요청으로 처리)

### 공통 응답 포맷
```json
{
  "success": true,
  "data": { },
  "error": null
}
```
```json
{
  "success": false,
  "data": null,
  "error": { "code": "PAYMENT_LIMIT_EXCEEDED", "message": "결제 한도를 초과했습니다." }
}
```

### 금액 단위
- 모든 금액은 원(KRW) 정수. 소수점 없음

---

## 6. 향후 확장 (Out of Scope)

아래는 의도적으로 현재 범위에서 제외한 항목이다. MVP의 초점은 결제 성공·취소·정산 흐름이며, 실패 알림 등 운영 편의 기능은 다음 단계로 둔다. 확장 시의 설계 방향만 미리 정리해 둔다.

| 항목 | 현재 상태 | 확장 방향 |
|---|---|---|
| 결제 실패 알림 | 실패/만료는 결제 상태만 `FAILED`로 전이하고 가맹점 알림은 보내지 않음 | `payment.failed` 이벤트를 추가하고 notification-service가 소비하여 실패 웹훅 전송 |
| 가상계좌 만료 알림 | `va.expired`는 payment-service만 소비 (결제 FAILED 전이용) | 위 `payment.failed`에 `failureCode: VA_EXPIRED`를 실어 동일 경로로 알림 |

**`payment.failed` 이벤트 (확장 시)**
```json
{
  "paymentKey": "pay_va_8c",
  "merchantId": 1001,
  "orderId": "ORDER-20260531-002",
  "method": "VIRTUAL_ACCOUNT",
  "failureCode": "VA_EXPIRED",
  "failedAt": "2026-06-01T14:30:00+09:00"
}
```

> 이 이벤트를 추가하면 실패도 성공/취소와 동일한 이벤트 드리븐 패턴으로 알림이 일관되게 처리된다. 현재는 범위 관리를 위해 제외.
