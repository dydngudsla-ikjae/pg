# 결제 플랫폼 — API 명세

> 전체 API의 요청/응답 payload 명세
> 관련 문서: [시스템 구조 & 정책](./service-spec.md) · [프로세스 & 메시지 설계](./service-process.md)

**공통 사항**
- Base 응답은 `{ success, data, error }` 래퍼로 감싼다. 아래 명세는 `data` 내부만 표기.
- 가맹점 호출 API는 `X-API-KEY` 헤더 필수.
- 금액은 원(KRW) 정수.
- **카드 원문 정보(`number`, `expiry`, `birthOrBizNo`, `pwd2digit`)는 Mock PG 호출에만 사용하고 DB에 저장하지 않는다.** 저장 가능 값은 `cardCompany`, `cardLast4`, `maskedCardNumber`, `pgTid`로 제한한다. 특히 `pwd2digit`은 어떤 경우에도 저장·로깅 금지.

---

## payment-service

### POST /v1/payments — 결제 승인 요청
- Header (외부 가맹점 호출): `Idempotency-Key` (필수), `X-API-KEY` (필수)
- Header (내부 서비스 호출, 예: billing): `Idempotency-Key` (필수), `X-Internal-Service`, `X-Merchant-Id`
- `Idempotency-Key`는 가맹점 범위에서 유일 (`unique(merchant_id, idempotency_key)`)
- 따라서 `method: BILLING` 내부 호출에는 `X-API-KEY`가 필요 없다.

**Request**
```json
{
  "orderId": "ORDER-20260531-001",
  "amount": 50000,
  "method": "CARD",
  "card": {
    "number": "1234-5678-9012-3456",
    "expiry": "12/28",
    "birthOrBizNo": "990101",
    "pwd2digit": "12"
  },
  "installmentMonths": 0,
  "orderName": "프리미엄 구독 1개월"
}
```
> `method`는 `CARD` | `VIRTUAL_ACCOUNT` | `BILLING` 등. `method: BILLING`인 경우 `card` 대신 `billingKey`를 보낸다 (정기결제 자동청구가 이 경로를 사용 — 아래 billing-service 참고).

**Response (200)**
```json
{
  "paymentKey": "pay_7f3a9b",
  "orderId": "ORDER-20260531-001",
  "status": "PAID",
  "amount": 50000,
  "method": "CARD",
  "cardCompany": "신한",
  "cardLast4": "3456",
  "maskedCardNumber": "1234-56**-****-3456",
  "approvedAt": "2026-05-31T14:23:01+09:00"
}
```
> 카드 원문은 응답에도 포함하지 않는다. PG 타임아웃 시 `status: "UNKNOWN"`으로 응답되며, 가맹점은 `GET /v1/payments/{paymentKey}` 조회로 최종 결과를 확인한다 (상태 확정 명령은 내부 프로세스가 담당).

### POST /internal/payments/{paymentKey}/verify — 결제 결과 확정 (내부 복구 전용)
- **내부 API.** 가맹점에 노출하지 않으며 `X-Internal-Service` 헤더로 호출한다.
- PG 타임아웃 등으로 `UNKNOWN` 상태가 된 결제를, payment-service 내부 스케줄러가 mock-pg 상태조회를 통해 최종 확정한다.
- 가맹점은 상태 확정을 직접 명령하지 않고 조회 API(`GET /v1/payments/{paymentKey}`)로만 결과를 확인한다.

**Response (200)**
```json
{
  "paymentKey": "pay_7f3a9b",
  "status": "PAID",
  "verifiedAt": "2026-05-31T14:23:10+09:00"
}
```
> 조회 결과에 따라 `PAID` 또는 `FAILED`로 확정된다.

### GET /v1/payments/{paymentKey} — 결제 단건 조회

**Response (200)**
```json
{
  "paymentKey": "pay_7f3a9b",
  "orderId": "ORDER-20260531-001",
  "status": "PARTIAL_CANCELLED",
  "amount": 50000,
  "cancelledAmount": 20000,
  "remainAmount": 30000,
  "method": "CARD",
  "cardCompany": "신한",
  "approvedAt": "2026-05-31T14:23:01+09:00"
}
```

### POST /v1/payments/{paymentKey}/cancel — 결제 취소 (부분/전액)
- Header: `Idempotency-Key` (필수), `X-API-KEY`

**Request**
```json
{
  "cancelAmount": 20000,
  "reason": "고객 변심 부분 환불",
  "merchantCancelId": "CANCEL-20260531-001"
}
```
> `merchantCancelId`: 가맹점이 부여하는 취소 식별자. `unique(merchant_id, merchant_cancel_id)` 제약으로 동일 취소 중복 요청을 차단하고, 가맹점이 자신의 취소 요청을 추적할 수 있다.

**Response (200)**
```json
{
  "paymentKey": "pay_7f3a9b",
  "cancelKey": "cnl_3d",
  "status": "PARTIAL_CANCELLED",
  "cancelledAmount": 20000,
  "remainAmount": 30000,
  "cancelledAt": "2026-05-31T18:00:00+09:00"
}
```

### GET /v1/payments — 결제 목록 조회
- Query: `merchantId`, `status`, `from`, `to`, `page`, `size`

**Response (200)**
```json
{
  "content": [
    {
      "paymentKey": "pay_7f3a9b",
      "orderId": "ORDER-20260531-001",
      "status": "PAID",
      "amount": 50000,
      "approvedAt": "2026-05-31T14:23:01+09:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```

---

## merchant-service

### POST /v1/merchants — 가맹점 등록

**Request**
```json
{
  "name": "마이쇼핑몰",
  "businessNo": "123-45-67890",
  "representativeName": "홍길동",
  "settlementBank": "088",
  "settlementAccount": "1234567890"
}
```

**Response (201)**
```json
{
  "merchantId": 1001,
  "name": "마이쇼핑몰",
  "status": "ACTIVE",
  "createdAt": "2026-05-31T09:00:00+09:00"
}
```

### GET /v1/merchants/{id} — 가맹점 조회

**Response (200)**
```json
{
  "merchantId": 1001,
  "name": "마이쇼핑몰",
  "businessNo": "123-45-67890",
  "status": "ACTIVE",
  "webhookUrl": "https://myshop.com/webhook",
  "createdAt": "2026-05-31T09:00:00+09:00"
}
```

### POST /v1/merchants/{id}/api-keys — API 키 발급

**Request**
```json
{
  "description": "운영 서버용 키"
}
```

**Response (201)**
```json
{
  "apiKey": "mk_live_a1b2c3d4e5",
  "description": "운영 서버용 키",
  "issuedAt": "2026-05-31T09:05:00+09:00"
}
```
> `apiKey`는 발급 시 1회만 노출된다.

### PUT /v1/merchants/{id}/webhook — 웹훅 URL 등록/수정

**Request**
```json
{
  "webhookUrl": "https://myshop.com/webhook"
}
```

**Response (200)**
```json
{
  "merchantId": 1001,
  "webhookUrl": "https://myshop.com/webhook",
  "updatedAt": "2026-05-31T09:10:00+09:00"
}
```

### PATCH /v1/merchants/{id}/status — 가맹점 상태 변경

**Request**
```json
{
  "status": "SUSPENDED",
  "reason": "정산 계좌 검증 실패"
}
```

**Response (200)**
```json
{
  "merchantId": 1001,
  "status": "SUSPENDED",
  "updatedAt": "2026-05-31T09:20:00+09:00"
}
```

---

## virtual-account-service

> **책임 경계 (A안)**: 가맹점은 가상계좌 결제를 `POST /v1/payments` (`method: VIRTUAL_ACCOUNT`)로 요청한다. payment-service가 결제 레코드(READY)를 생성하고, 내부적으로 virtual-account-service에 계좌 발급을 요청한다. virtual-account-service는 **계좌 발급과 입금 콜백만** 책임지며 결제 상태는 소유하지 않는다.

### (가맹점용) POST /v1/payments — `method: VIRTUAL_ACCOUNT`

**Request**
```json
{
  "orderId": "ORDER-20260531-002",
  "amount": 30000,
  "method": "VIRTUAL_ACCOUNT",
  "virtualAccount": {
    "bankCode": "088",
    "depositorName": "홍길동",
    "expireHours": 24
  },
  "orderName": "도서 구매"
}
```

**Response (200)**
```json
{
  "paymentKey": "pay_va_8c",
  "orderId": "ORDER-20260531-002",
  "status": "READY",
  "method": "VIRTUAL_ACCOUNT",
  "amount": 30000,
  "virtualAccount": {
    "virtualAccountNo": "88812345678901",
    "bankCode": "088",
    "status": "WAITING_DEPOSIT",
    "expiresAt": "2026-06-01T14:30:00+09:00"
  }
}
```
> Payment=READY, VirtualAccount=WAITING_DEPOSIT 한 쌍으로 응답된다.

### (내부) POST /internal/virtual-accounts — 계좌 발급
- payment-service → virtual-account-service 내부 호출. 가맹점에 직접 노출되지 않는다.

**Request**
```json
{
  "paymentKey": "pay_va_8c",
  "amount": 30000,
  "bankCode": "088",
  "depositorName": "홍길동",
  "expireHours": 24
}
```

**Response (200)**
```json
{
  "virtualAccountNo": "88812345678901",
  "bankCode": "088",
  "status": "WAITING_DEPOSIT",
  "expiresAt": "2026-06-01T14:30:00+09:00"
}
```

### GET /v1/virtual-accounts/{paymentKey} — 가상계좌 조회

**Response (200)**
```json
{
  "paymentKey": "pay_va_8c",
  "virtualAccountNo": "88812345678901",
  "bankCode": "088",
  "amount": 30000,
  "status": "DEPOSITED",
  "expiresAt": "2026-06-01T14:30:00+09:00",
  "depositedAt": "2026-05-31T16:10:00+09:00"
}
```
> 여기 `status`는 VirtualAccountStatus (`WAITING_DEPOSIT`/`DEPOSITED`/`EXPIRED`). 결제 상태는 `GET /v1/payments/{paymentKey}`로 확인.

### POST /v1/virtual-accounts/deposit-callback — 입금 콜백 (Mock 은행 → 우리)

**Request**
```json
{
  "virtualAccountNo": "88812345678901",
  "depositAmount": 30000,
  "depositedAt": "2026-05-31T16:10:00+09:00",
  "bankTxId": "BANK-TX-9981"
}
```
> `bankTxId` 기준 멱등 처리. 동일 콜백 중복 수신 시 1회만 반영.

**Response (200)**
```json
{
  "result": "ACCEPTED",
  "paymentKey": "pay_va_8c",
  "status": "DEPOSITED"
}
```
> 입금 확정 후 `va.deposited` 이벤트 발행 → payment-service가 결제를 PAID로 전이.

---

## billing-service

### POST /v1/billing/keys — 빌링키 발급 (카드 등록)

**Request**
```json
{
  "customerId": "CUST-77",
  "card": {
    "number": "1234-5678-9012-3456",
    "expiry": "12/28",
    "birthOrBizNo": "990101",
    "pwd2digit": "12"
  }
}
```

**Response (201)**
```json
{
  "billingKey": "bky_a9f",
  "customerId": "CUST-77",
  "cardCompany": "신한",
  "cardLast4": "3456",
  "issuedAt": "2026-05-31T10:00:00+09:00"
}
```

### DELETE /v1/billing/keys/{billingKey} — 빌링키 삭제

**Response (204)** — 본문 없음

### POST /v1/billing/subscriptions — 구독 생성

**Request**
```json
{
  "billingKey": "bky_a9f",
  "planName": "프리미엄 월간",
  "amount": 9900,
  "cycle": "MONTHLY",
  "firstChargeDate": "2026-06-01"
}
```

**Response (201)**
```json
{
  "subscriptionId": 5001,
  "billingKey": "bky_a9f",
  "planName": "프리미엄 월간",
  "amount": 9900,
  "cycle": "MONTHLY",
  "status": "ACTIVE",
  "nextChargeDate": "2026-06-01"
}
```

### DELETE /v1/billing/subscriptions/{id} — 구독 해지

**Response (200)**
```json
{
  "subscriptionId": 5001,
  "status": "CANCELLED",
  "cancelledAt": "2026-05-31T11:00:00+09:00"
}
```

### POST /v1/billing/charge — 수동 즉시 청구 (테스트용)

**Request**
```json
{
  "subscriptionId": 5001
}
```

**Response (200)**
```json
{
  "subscriptionId": 5001,
  "paymentKey": "pay_sub_4e",
  "amount": 9900,
  "status": "PAID",
  "chargedAt": "2026-05-31T11:05:00+09:00"
}
```

> **청구 시 payment-service 호출 방식**: billing-service(스케줄러·수동 청구 공통)는 아래와 같이 `POST /v1/payments`를 `method: BILLING`으로 호출한다. 가맹점 API Key 대신 내부 인증 헤더(`X-Internal-Service: billing-service`, `X-Merchant-Id: 1001`)를 사용하며, 결제 레코드는 payment-service가 소유한다.
> ```json
> {
>   "orderId": "SUB-5001-202606",
>   "amount": 9900,
>   "method": "BILLING",
>   "billingKey": "bky_a9f",
>   "orderName": "프리미엄 월간 구독"
> }
> ```

---

## settlement-service

### GET /v1/settlements — 정산 내역 조회
- Query: `merchantId`, `settlementDate`

**Response (200)**
```json
{
  "content": [
    {
      "settlementId": 7001,
      "merchantId": 1001,
      "settlementDate": "2026-05-31",
      "totalAmount": 1500000,
      "feeAmount": 37500,
      "payoutAmount": 1462500,
      "status": "COMPLETED"
    }
  ]
}
```

### GET /v1/settlements/{id} — 정산 상세

**Response (200)**
```json
{
  "settlementId": 7001,
  "merchantId": 1001,
  "settlementDate": "2026-05-31",
  "totalAmount": 1500000,
  "feeRate": 0.025,
  "feeAmount": 37500,
  "payoutAmount": 1462500,
  "status": "COMPLETED",
  "items": [
    { "paymentKey": "pay_7f3a9b", "type": "PAYMENT", "amount": 50000, "fee": 1250, "payout": 48750 },
    { "paymentKey": "pay_3a1c", "type": "CANCEL", "amount": -20000, "fee": -500, "payout": -19500 },
    { "paymentKey": "pay_9f2d", "type": "ADJUSTMENT", "amount": -30000, "fee": -750, "payout": -29250 }
  ]
}
```

### POST /v1/settlements/run — 정산 배치 수동 실행 (관리자/테스트용)

**Request**
```json
{
  "settlementDate": "2026-05-31"
}
```

**Response (202)**
```json
{
  "jobId": "settlement-job-20260531",
  "settlementDate": "2026-05-31",
  "status": "RUNNING"
}
```

---

## mock-pg (시뮬레이터)

### POST /mock-pg/approve — 승인 (성공/실패/지연 시뮬레이션)

**Request**
```json
{
  "paymentKey": "pay_7f3a9b",
  "amount": 50000,
  "cardNumber": "1234-5678-9012-3456",
  "installmentMonths": 0,
  "simulate": "SUCCESS"
}
```
> `simulate`: `SUCCESS` | `DECLINE` | `TIMEOUT` (테스트 시나리오 제어)

**Response (200)**
```json
{
  "approved": true,
  "pgTid": "PGTID-20260531-0001",
  "cardCompany": "신한",
  "approvedAt": "2026-05-31T14:23:01+09:00"
}
```

### GET /mock-pg/payments/by-payment-key/{paymentKey} — 승인 상태 조회 (타임아웃 복구용)
- payment-service의 verify 로직이 호출. 타임아웃으로 결과를 못 받은 결제의 실제 처리 여부를 확인한다.

**Response (200)**
```json
{
  "paymentKey": "pay_7f3a9b",
  "found": true,
  "approved": true,
  "pgTid": "PGTID-20260531-0001",
  "approvedAt": "2026-05-31T14:23:01+09:00"
}
```
> `found: false`이면 PG에 승인 기록이 없는 것이므로 payment-service는 결제를 `FAILED`로 확정한다.

### POST /mock-pg/cancel — 취소

**Request**
```json
{
  "pgTid": "PGTID-20260531-0001",
  "cancelAmount": 20000
}
```

**Response (200)**
```json
{
  "cancelled": true,
  "pgCancelTid": "PGCNL-20260531-0001",
  "cancelledAt": "2026-05-31T18:00:00+09:00"
}
```

### POST /mock-bank/deposit — 가상계좌 입금 발생

**Request**
```json
{
  "virtualAccountNo": "88812345678901",
  "amount": 30000,
  "depositorName": "홍길동"
}
```

**Response (200)**
```json
{
  "deposited": true,
  "bankTxId": "BANK-TX-9981",
  "depositedAt": "2026-05-31T16:10:00+09:00"
}
```
