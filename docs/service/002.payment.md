# 결제 서비스 (payment-service)

> 결제 승인·취소·조회를 담당하는 핵심 서비스.
> 시스템의 모든 결제(카드/가상계좌/정기결제)는 이 서비스가 단일 소유자로서 생성·상태 전이한다.

---

## 1. 책임 범위

| 한다 | 안 한다 |
|---|---|
| 결제 승인 / 취소 / 부분취소 | 가맹점 인증 (Gateway + merchant-service) |
| 모든 결제 레코드의 생성·상태 전이 (단일 소유자) | 가상계좌 채번 (virtual-account-service) |
| 멱등성·동시성 제어 | 빌링키 발급 (billing-service) |
| PG 승인/취소 호출 | 정산 계산 (settlement-service) |
| 결제 이벤트 발행 (payment.paid / payment.cancelled) | 웹훅 전송 (notification-service) |
| 타임아웃 결제 복구 (verify) | |

> 가상계좌·정기결제도 결제 레코드 자체는 payment-service가 소유한다. 도메인 서비스(VA, billing)는 부가 정보(계좌·빌링키)만 책임진다.

---

## 2. 정책

### 2.1 결제 상태 (PaymentStatus)

| 상태 | 의미 |
|---|---|
| READY | 결제 생성됨, 승인 전 |
| PAID | 승인 완료 |
| PARTIAL_CANCELLED | 부분 취소됨 |
| CANCELLED | 전액 취소됨 |
| FAILED | 승인 실패 (거절/만료 등) |
| UNKNOWN | PG 타임아웃 등 결과 불명 (verify로 확정 필요) |

**허용 상태 전이**
```
READY → PAID
READY → FAILED
READY → UNKNOWN
UNKNOWN → PAID
UNKNOWN → FAILED
PAID → PARTIAL_CANCELLED
PAID → CANCELLED
PARTIAL_CANCELLED → PARTIAL_CANCELLED
PARTIAL_CANCELLED → CANCELLED
```
> 그 외 전이는 차단. 상태 전이는 엔티티 내부 메서드(approve/cancel/fail)에서만 수행하고 외부에서 직접 setStatus 불가.

### 2.2 결제 수단 (PaymentMethod)

| 값 | 설명 |
|---|---|
| CARD | 신용/체크카드 |
| VIRTUAL_ACCOUNT | 가상계좌 |
| BILLING | 빌링키 기반 정기결제 |

### 2.3 멱등성 & 동시성

| 항목 | 정책 |
|---|---|
| 멱등성 키 | `Idempotency-Key` 헤더 필수. 식별은 **(merchantId + HTTP 메서드 + 엔드포인트 경로 + idempotency_key)** 조합으로 한다. 같은 키라도 다른 API/메서드면 별개 요청으로 취급 (토스페이먼츠와 동일 방식) |
| 중복 재요청 | 동일 조합 + 처리 완료 → 캐싱된 응답 반환(첫 응답과 동일). 동일 키 + 다른 요청 body → 422 (요청 불일치). **처리 중인 동일 요청 → 409 Conflict** (기다렸다 재시도 안내) |
| 키 보관 | 15일 (결제·환불은 사후에 발생할 수 있어 길게 잡음). 만료 후 동일 키는 신규 요청으로 처리 |
| 멱등 재시도 주의 | 에러 응답을 받았을 때 **키를 바꿔 재요청하지 않는다.** 원인 확인 후 같은 키로 재시도해야 안전 (키를 바꾸면 중복 처리 위험) |
| 동시성 제어 | **Redis 분산락 + DB unique 제약 이중 방어** (아래 상세) |

**왜 이중 방어인가**
- Redis 분산락: 같은 키로 동시에 들어온 요청을 **앞단에서** 직렬화 → PG 중복 호출 방지
- DB unique 제약: 락이 어떤 이유로 풀려도 **최종 방어선**으로 중복 저장 차단

**Redis 락 설계**
| 항목 | 값 |
|---|---|
| 락 키 | `lock:payment:{merchantId}:{idempotencyKey}` |
| TTL | PG 타임아웃(3s)보다 넉넉히 긴 **10초** (작업 중 락이 먼저 풀리는 것 방지) |
| 획득 실패 | 동일 결제가 처리 중 → 409 Conflict (또는 짧게 대기 후 캐시 응답 확인) |
| 해제 | 처리 완료 시 명시적 해제. 비정상 종료 시 TTL로 자동 해제 |

> TTL을 PG 타임아웃보다 길게 잡는 이유: 락이 PG 응답을 기다리는 도중 만료되면, 같은 요청이 동시에 PG를 또 호출할 수 있다. 그래서 "최대 작업 시간 + 여유"로 잡는다.

### 2.4 PG 호출 정책

| 항목 | 정책 |
|---|---|
| 타임아웃 | 3초. 초과 시 결제 `UNKNOWN`, verify로 확정 |
| 재시도 | 연결 실패 등은 최대 2회 (지수 백오프 1s→2s). **단, 타임아웃은 재시도 안 함** (중복 승인 위험) |
| 명확한 거절 | DECLINE 응답은 `UNKNOWN` 거치지 않고 `READY → FAILED` 직접 전이 |

### 2.5 결제 한도 & 취소

| 항목 | 정책 |
|---|---|
| 결제 한도 | 건당 최대 1,000만원. 초과 시 거절 |
| 부분취소 | 잔여 금액 내 N회 가능. 누적 취소액 ≤ 원금 |
| 취소 식별 | `merchantCancelId`로 가맹점의 중복 취소 차단: `unique(merchant_id, merchant_cancel_id)` |

### 2.6 카드정보 저장 제한

- 카드 원문(`number`, `expiry`, `birthOrBizNo`, `pwd2digit`)은 **PG 호출에만 사용, DB 저장 금지**.
- 저장 가능 값: `cardCompany`, `cardLast4`, `maskedCardNumber`, `pgTid`.
- `pwd2digit`은 저장·로깅 절대 금지.

---

## 3. ERD

```
payment (1) ──< (N) payment_history
payment (1) ──< (N) cancel
payment (1) ──< (1) idempotency_key
payment (1) ──< (N) outbox_event   (논리적 연관; 실제로는 aggregate id로 참조)
```

### payment

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| merchant_id | BIGINT | 가맹점 ID |
| order_id | VARCHAR(64) | 가맹점 주문번호 |
| payment_key | VARCHAR(64) UNIQUE | 우리 시스템 결제키 |
| status | payment_status | 상태 |
| method | payment_method | 결제수단 |
| amount | BIGINT | 결제 금액 |
| cancelled_amount | BIGINT | 누적 취소액 (기본 0) |
| pg_tid | VARCHAR(128) | PG 거래번호 |
| card_company | VARCHAR(32) | 카드사 |
| card_last4 | VARCHAR(4) | 카드 끝 4자리 |
| masked_card_number | VARCHAR(20) | 마스킹된 카드번호 |
| installment_months | INT | 할부 개월 (0=일시불) |
| failure_code | VARCHAR(32) | 실패 코드 (예: VA_EXPIRED, DECLINED) |
| failure_message | VARCHAR(256) | 실패 메시지 |
| paid_at | TIMESTAMP | 승인 시각 |
| created_at / updated_at | TIMESTAMP | |
| | | `unique(merchant_id, order_id)` |

### payment_history
상태 변경 이력 (감사 로그). payment_id, from_status, to_status, reason, changed_by, created_at.

### cancel
부분취소 지원. payment_id, cancel_key, merchant_cancel_id, cancel_amount, remain_amount, reason, pg_cancel_tid, cancelled_at. `unique(merchant_id, merchant_cancel_id)`.

### idempotency_key
idempotency_key, merchant_id, http_method, endpoint, payment_id, request_hash, response_body, status(PROCESSING/COMPLETED), expired_at. `unique(merchant_id, http_method, endpoint, idempotency_key)`. `status=PROCESSING` 중 동일 요청이 오면 409 반환, `COMPLETED`면 저장된 response_body 반환.

### outbox_event (Outbox 패턴 핵심)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| aggregate_type | VARCHAR(32) | 예: PAYMENT |
| aggregate_id | VARCHAR(64) | payment_key |
| event_type | VARCHAR(64) | payment.paid 등 |
| payload | JSONB | CloudEvents 형식 이벤트 본문 |
| status | VARCHAR(16) | PENDING / PUBLISHED / FAILED |
| retry_count | INT | 발행 재시도 횟수 |
| created_at | TIMESTAMP | |
| published_at | TIMESTAMP | 발행 완료 시각 |

```sql
CREATE TABLE outbox_event (
    id             BIGSERIAL    PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        JSONB        NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMP
);
CREATE INDEX idx_outbox_status ON outbox_event(status, created_at);
```

---

## 4. 이벤트 설계 (CloudEvents)

Kafka 메시지는 [CloudEvents](https://cloudevents.io/) 1.0 structured content mode를 따른다. 표준 봉투로 어떤 컨슈머든 일관되게 파싱·추적할 수 있다.

**공통 봉투**
```json
{
  "specversion": "1.0",
  "id": "evt_9f8c...",
  "source": "/payment-service",
  "type": "com.payment.payment.paid",
  "time": "2026-05-31T14:23:01Z",
  "datacontenttype": "application/json",
  "subject": "pay_7f3a9b",
  "data": { }
}
```
- `id`: 이벤트 고유 ID. 컨슈머는 이 값으로 멱등 처리(중복 수신 무시).
- `type`: 역방향 도메인 표기 (`com.payment.payment.paid`).
- `subject`: paymentKey. 파티션 키로도 사용 → 동일 결제 이벤트 순서 보장.

**payment.paid `data`**
```json
{
  "paymentKey": "pay_7f3a9b",
  "merchantId": 1001,
  "orderId": "ORDER-20260531-001",
  "amount": 50000,
  "method": "CARD",
  "paidAt": "2026-05-31T14:23:01+09:00"
}
```

**payment.cancelled `data`**
```json
{
  "paymentKey": "pay_7f3a9b",
  "cancelKey": "cnl_3d",
  "merchantCancelId": "CANCEL-20260531-001",
  "merchantId": 1001,
  "cancelAmount": 20000,
  "remainAmount": 30000,
  "fullyCancelled": false,
  "cancelledAt": "2026-05-31T18:00:00+09:00"
}
```

| 토픽 | 프로듀서 | 컨슈머 |
|---|---|---|
| payment.paid | payment-service | settlement-service, notification-service |
| payment.cancelled | payment-service | settlement-service, notification-service |

> 소비 측(정산/알림)의 컨슈머 재처리·DLQ 설계는 각 서비스 문서에서 다룬다. payment-service는 **발행이 유실되지 않도록 보장**하는 책임까지 진다 (Outbox).

---

## 5. API 스펙

> 공통: 응답은 `{ success, data, error }` 래퍼. 아래는 `data` 내부만 표기. 금액은 원(KRW) 정수.
> 대상 클라이언트를 각 API에 표기 (게이트웨이 분리 대상 — 8장 참고).

### POST /v1/payments — 결제 승인 요청
- 대상: 가맹점(외부) / 내부 서비스(billing)
- Header (외부): `Idempotency-Key`(필수), `X-API-KEY`(필수)
- Header (내부): `Idempotency-Key`(필수), `X-Internal-Service`, `X-Merchant-Id`

**Request (CARD)**
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
> `method: VIRTUAL_ACCOUNT`이면 `card` 대신 `virtualAccount`(bankCode, depositorName, expireHours), `method: BILLING`이면 `billingKey`를 보낸다.

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
> 타임아웃 시 `status: "UNKNOWN"`. 가맹점은 조회 API로 최종 결과 확인.

### GET /v1/payments/{paymentKey} — 결제 단건 조회
- 대상: 가맹점(외부)

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

### GET /v1/payments — 결제 목록 조회
- 대상: 가맹점(외부)
- Query: `status`, `from`, `to`, `page`, `size` (merchantId는 인증에서 추출)

**Response (200)**
```json
{
  "content": [
    { "paymentKey": "pay_7f3a9b", "orderId": "ORDER-20260531-001", "status": "PAID", "amount": 50000, "approvedAt": "2026-05-31T14:23:01+09:00" }
  ],
  "page": 0, "size": 20, "totalElements": 1
}
```

### POST /v1/payments/{paymentKey}/cancel — 결제 취소
- 대상: 가맹점(외부)
- Header: `Idempotency-Key`(필수), `X-API-KEY`(필수)

**Request**
```json
{
  "cancelAmount": 20000,
  "reason": "고객 변심 부분 환불",
  "merchantCancelId": "CANCEL-20260531-001"
}
```

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

### POST /internal/payments/{paymentKey}/verify — 결제 결과 확정
- 대상: 내부 스케줄러. `X-Internal-Service` 필요. 외부 비노출.
- `UNKNOWN` 결제를 mock-pg 상태조회로 `PAID`/`FAILED` 확정.

**Response (200)**
```json
{ "paymentKey": "pay_7f3a9b", "status": "PAID", "verifiedAt": "2026-05-31T14:23:10+09:00" }
```

---

## 6. 프로세스 & 트랜잭션 경계

### 6.1 카드 결제 승인 (Outbox 적용)

```
가맹점 ──> POST /v1/payments (Idempotency-Key)
  ▼
[payment-service]
  │ ① Redis 분산락 획득 lock:payment:{merchantId}:{key} (TTL 10s)
  │     실패 → 409 (처리 중) 또는 캐시 응답 확인
  │ ② 멱등성 키 조회 → 있으면 캐시 응답 반환 후 종료
  │ ┌─ 물리 트랜잭션 T1 시작 ─────────────────────┐
  │ │ ③ payment READY 저장, 한도 검증              │
  │ │ ④ idempotency_key 저장 (request_hash)        │
  │ └──────────────────────────────────────────── 커밋 ┘
  │ ⑤ Mock PG 승인 호출 (동기, 3s)  ← 트랜잭션 밖!
  │ ┌─ 물리 트랜잭션 T2 시작 ─────────────────────┐
  │ │ ⑥ payment PAID 전이, 카드 메타정보 저장       │
  │ │ ⑦ payment_history 기록                       │
  │ │ ⑧ idempotency 응답 캐싱                       │
  │ │ ⑨ outbox_event(payment.paid, PENDING) 저장   │  ← 결제 저장과 같은 트랜잭션
  │ └──────────────────────────────────────────── 커밋 ┘
  │ ⑩ Redis 락 해제
  │ ⑪ 가맹점에 200 응답
  ▼
[Outbox Publisher] (별도 스케줄러/CDC)
  │ ⑫ status=PENDING 폴링 → Kafka 발행 → PUBLISHED 마킹
  ▼
Kafka payment.paid → settlement / notification
```

**트랜잭션 경계 핵심**
- **PG 호출(⑤)은 트랜잭션 밖**에 둔다. 외부 호출을 DB 트랜잭션 안에 넣으면 커넥션을 오래 점유하고, PG 지연이 DB 락으로 번진다.
- **이벤트 발행을 직접 하지 않고 outbox_event 저장(⑨)으로 대체**한다. 결제 PAID 저장과 이벤트가 **같은 트랜잭션(T2)**이라 원자적이다. → 결제는 저장됐는데 이벤트만 유실되는 상황이 원천 차단.
- 실제 Kafka 발행(⑫)은 별도 퍼블리셔가 outbox를 읽어 처리. 발행 실패해도 PENDING으로 남아 재시도됨.

### 6.2 단계별 실패 / 서버 다운 복구

| 죽는 시점 | 결과 | 복구 |
|---|---|---|
| ① 락 획득 후 | 락은 TTL로 자동 해제 | 가맹점 재요청 시 정상 처리 |
| ③④ T1 커밋 전 | 롤백, 아무것도 안 남음 | 재요청 시 신규 처리 |
| ③④ T1 커밋 후 ~ ⑤ PG 호출 전 | payment=READY, idempotency 저장됨 | 재요청 시 멱등 키 히트 → verify로 상태 확인 후 진행 |
| ⑤ PG 호출 중/후 ~ T2 전 | PG는 승인됐을 수 있으나 우리 DB는 READY | **verify 스케줄러**가 UNKNOWN/READY 결제를 mock-pg 조회 → PAID/FAILED 확정 + outbox 기록 |
| ⑥~⑨ T2 커밋 전 | 롤백, READY 유지 | verify로 복구 |
| ⑨ 커밋 후 ~ ⑫ 발행 전 | payment=PAID, outbox=PENDING | **Outbox Publisher**가 PENDING 폴링 → 발행 (유실 없음) |
| ⑫ 발행 중 | 중복 발행 가능 | 컨슈머가 CloudEvents `id`로 멱등 처리 |

> 핵심: 어느 시점에 죽어도 (1) verify로 결제 상태를 확정하고 (2) Outbox로 이벤트를 결국 발행한다. "at-least-once 발행 + 컨슈머 멱등"으로 정확히 한 번 효과를 낸다.
>
> verify는 개별 결제의 즉시 복구 수단이고, 이와 별개로 **reconciliation(정산 대사) 배치**가 PG 거래 내역 전체와 우리 DB를 주기적으로 대조해 누락·불일치를 잡는 안전망 역할을 한다. (개별 복구 + 전수 대조의 2단 방어)

### 6.3 결제 취소

```
가맹점 ──> POST /v1/payments/{paymentKey}/cancel (Idempotency-Key)
  ▼
[payment-service]
  │ ① Redis 락 (lock:cancel:{paymentKey}, TTL 10s)
  │     실패 → 409 (처리 중)
  │ ② 멱등성 키 조회 → 있으면 캐시 응답 반환 후 종료
  │ ③ merchantCancelId 중복 검증 (unique(merchant_id, merchant_cancel_id))
  │ ④ 상태/금액 검증 (PAID/PARTIAL_CANCELLED, 누적 ≤ 원금)
  │ ⑤ Mock PG 취소 호출 (트랜잭션 밖, 3s)
  │ ┌─ 물리 트랜잭션 ────────────────────────┐
  │ │ ⑥ cancel 레코드 생성, 상태 전이          │
  │ │ ⑦ payment_history 기록                  │
  │ │ ⑧ idempotency 응답 캐싱                  │
  │ │ ⑨ outbox_event(payment.cancelled) 저장   │
  │ └──────────────────────────────── 커밋 ┘
  │ ⑩ 락 해제, 응답
```

**단계별 실패 / 서버 다운 복구**

| 죽는 시점 | 결과 | 복구 |
|---|---|---|
| ① 락 획득 후 | 락은 TTL로 자동 해제 | 재요청 시 정상 처리 |
| ②~④ 검증 단계 | 아무것도 안 남음 | 재요청 시 신규 처리 |
| ⑤ PG 취소 호출 중/후 ~ 트랜잭션 전 | **PG는 취소됐을 수 있으나 우리 DB는 미반영** (PAID 유지) | 가맹점이 같은 멱등키로 재요청 시 PG가 중복 취소를 막아줌(PG도 멱등). 멱등키조차 유실된 경우는 **reconciliation 배치**가 PG와 DB를 대조해 보정 (아래) |
| ⑥~⑨ 트랜잭션 커밋 전 | 롤백, 취소 미반영 | 위와 동일 |
| ⑨ 커밋 후 ~ Outbox 발행 전 | cancel 반영됨, outbox=PENDING | **Outbox Publisher**가 PENDING 폴링 → 발행 (유실 없음) |
| Outbox 발행 중 | 중복 발행 가능 | 컨슈머가 CloudEvents `id`로 멱등 처리 |

> **취소가 승인보다 까다로운 지점**: 승인은 결과를 모르면 "안 됐으면 다시"가 되지만, 취소는 PG에서 이미 환불이 나갔는데 우리 DB만 미반영이면 재요청 시 **이중 환불** 위험이 있다. 실무는 이를 다음 3중으로 막는다.
> 1. **멱등키 + PG 자체 멱등**: 가맹점이 같은 멱등키로 재요청하면 우리도, PG도 중복 취소를 막는다. (PG는 메서드/엔드포인트/키 조합으로 식별)
> 2. **PG 취소 거래 식별자(`pg_cancel_tid`)**: 우리 DB 반영 시 PG 취소번호 기준으로 중복 반영을 차단.
> 3. **reconciliation(정산 대사) 배치**: 서버 다운 등으로 멱등 기록조차 없을 때를 대비해, 주기적으로 PG의 거래 내역과 우리 DB를 대조하여 불일치(우리는 미취소인데 PG는 취소됨 등)를 찾아 보정한다. 실무 PG는 야간 배치 + 준실시간 대사를 함께 운영한다.

---

## 7. 트랜잭션 경계 요약

| 작업 | 물리 트랜잭션 | 외부 호출 위치 | 이벤트 |
|---|---|---|---|
| 결제 승인 | T1(READY+멱등), T2(PAID+outbox) 분리 | PG 호출은 T1·T2 사이 (트랜잭션 밖) | outbox로 원자 저장 |
| 결제 취소 | 단일 (cancel+history+outbox) | PG 취소는 트랜잭션 밖 | outbox로 원자 저장 |
| verify (승인) | 단일 (상태확정+outbox) | mock-pg 조회는 트랜잭션 밖 | 필요 시 outbox |
| verify (취소) | 단일 (취소반영+outbox) | mock-pg 취소조회는 트랜잭션 밖 | 필요 시 outbox |

> 논리 트랜잭션(결제→정산→알림)은 단일 DB 트랜잭션이 아니라 **이벤트 기반 최종 일관성**으로 묶는다. payment-service는 자기 DB 일관성 + 이벤트 발행 보장까지 책임지고, 그 이후는 컨슈머가 책임진다.

---

## 8. 강화 체크리스트 적용 결과

| 강화 항목 | 적용 |
|---|---|
| CloudEvents | payment.paid/cancelled를 CloudEvents 1.0 봉투로 발행 (4장) |
| Redis 락 TTL | 멱등성 동시성 제어에 분산락 + TTL 10s (2.3) |
| 트랜잭션 경계 | T1/T2 분리, PG 호출은 트랜잭션 밖, 이벤트는 Outbox (6장) |
| 단계별 실패 복구 | 죽는 시점별 복구 표 (승인 6.2 / 취소 6.3) + verify(개별 복구) + reconciliation 배치(전수 대조) + Outbox |
| 컨슈머 재처리 | 발행 보장은 여기서, 소비 재처리/DLQ는 정산·알림 문서로 위임 |
| 게이트웨이 분리 | 각 API에 대상 클라이언트 표기 (외부 가맹점 / 내부 서비스 / 내부 스케줄러) (5장) |
