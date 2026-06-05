# 정기결제 서비스 (billing-service)

> 빌링키 발급(카드 등록)과 구독 기반 자동청구를 담당하는 서비스.
> 결제 자체는 소유하지 않는다 — 청구 시 payment-service를 `method: BILLING`으로 호출하고, 결제 레코드는 payment-service가 소유한다.

---

## 1. 책임 범위

| 한다 | 안 한다 |
|---|---|
| 빌링키 발급/폐기 (카드 등록) | 결제 레코드 생성·승인 (payment-service) |
| 구독 생성/해지 | 실제 카드 승인 (PG) |
| 자동청구 스케줄링 (매일 배치) | 정산 (settlement-service) |
| 청구 실패 재시도 관리 | 웹훅 전송 (notification-service) |
| 구독 상태 관리 (활성/정지/해지) | |

> 청구 성공분은 payment-service가 `payment.paid`를 발행하므로, 정산·알림 흐름은 일반 결제와 자동으로 합류한다.

---

## 2. 정책

### 2.1 빌링키 정책

| 항목 | 정책 |
|---|---|
| 발급 | 카드 등록 시 PG에 카드 정보 전달 → PG가 빌링키(토큰) 발급 → 우리는 **빌링키만 저장** |
| 카드정보 | 카드 원문은 **절대 저장하지 않음**. 저장 값은 `billing_key`, `card_company`, `card_last4`뿐 |
| 발급 시 검증 | 단순 등록(형식 검증만). 첫 청구 시 실제 승인으로 유효성 확인 |
| 폐기 | `revoked_at` 설정으로 무효화. 연결된 구독도 해지 처리 |

> **실무 노트**: 실무에서는 발급 시 소액(예: 100원) 결제 후 즉시 취소로 카드 유효성을 검증하는 경우가 많다. 본 프로젝트는 범위 관리를 위해 단순 등록으로 두고, 이 방식은 확장 포인트로만 둔다.

### 2.2 구독 상태 (SubscriptionStatus)

| 상태 | 의미 | 자동청구 |
|---|---|---|
| ACTIVE | 정상 구독 | O |
| SUSPENDED | 청구 실패 누적으로 정지 | X (재개 시 ACTIVE) |
| CANCELLED | 해지됨 | X |

### 2.3 자동청구 정책

| 항목 | 정책 |
|---|---|
| 실행 | 매일 1회 Spring Batch. `next_charge_date <= 오늘` 인 ACTIVE 구독 조회 |
| 통합 처리 | 신규 청구와 재시도를 **하나의 배치에서 통합** 처리 (`next_charge_date` 기준 단일 쿼리) |
| 건별 격리 | 각 구독은 **건별 트랜잭션**. 한 건 실패가 전체 배치를 멈추지 않음 |
| 성공 시 | `next_charge_date`를 다음 주기로 갱신 (월간이면 +1개월) |
| 멱등성 | payment-service 호출 시 멱등키 = `구독ID + 청구주기`(예: `SUB-5001-202606`). 같은 주기는 두 번 청구 불가 |

### 2.4 청구 실패 재시도

| 항목 | 정책 |
|---|---|
| 재시도 기간 | 최대 3일, 일 1회 (배치가 매일 돌며 재시도분도 같이 집음) |
| 재시도 카운트 | `retry_count` 증가. 실패 시 `next_charge_date`를 다음 날로 설정해 재시도 대상에 포함 |
| 정지 | 3회 실패 시 구독 `SUSPENDED`, 청구 중단 |
| 멱등키 | 재시도도 같은 주기 멱등키 사용 → 이전 시도가 실제로는 성공했는데 응답만 유실된 경우, payment-service가 캐시 응답을 돌려줘 이중 청구 방지 |

> 멱등키를 `구독ID + 주기`로 잡는 이유: 재시도가 위험한 건 "지난번에 실제로는 결제됐는데 우리가 실패로 알고 또 청구"하는 경우다. 같은 주기 멱등키면 payment-service가 첫 결과를 그대로 반환하므로 이중 청구가 구조적으로 불가능하다.

---

## 3. ERD

```
billing_key (1) ──< (N) subscription
subscription (1) ──< (N) billing_charge_log
```

### billing_key

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| merchant_id | BIGINT | 가맹점 ID |
| customer_id | VARCHAR(64) | 가맹점이 부여한 고객 식별자 |
| billing_key | VARCHAR(64) UNIQUE | PG 발급 빌링키(토큰) |
| card_company | VARCHAR(32) | 카드사 |
| card_last4 | VARCHAR(4) | 카드 끝 4자리 |
| revoked_at | TIMESTAMP | 폐기 시각 (NULL이면 유효) |
| created_at | TIMESTAMP | |

### subscription

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| merchant_id | BIGINT | 가맹점 ID |
| billing_key_id | BIGINT FK | 사용할 빌링키 |
| plan_name | VARCHAR(128) | 구독 상품명 |
| amount | BIGINT | 청구 금액 |
| cycle | VARCHAR(16) | MONTHLY 등 |
| status | subscription_status | ACTIVE / SUSPENDED / CANCELLED |
| next_charge_date | DATE | 다음 청구(또는 재시도) 예정일 |
| retry_count | INT | 현재 주기 누적 실패 횟수 (성공 시 0 리셋) |
| current_period | VARCHAR(8) | 현재 청구 주기 (예: 202606). 멱등키 구성 요소 |
| created_at / updated_at | TIMESTAMP | |

### billing_charge_log
청구 시도 이력 (감사·추적). subscription_id, period, payment_key, idempotency_key, result(SUCCESS/FAILED), failure_code, attempted_at.

```sql
CREATE TYPE subscription_status AS ENUM ('ACTIVE', 'SUSPENDED', 'CANCELLED');

CREATE TABLE billing_key (
    id           BIGSERIAL    PRIMARY KEY,
    merchant_id  BIGINT       NOT NULL,
    customer_id  VARCHAR(64)  NOT NULL,
    billing_key  VARCHAR(64)  NOT NULL UNIQUE,
    card_company VARCHAR(32),
    card_last4   VARCHAR(4),
    revoked_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE subscription (
    id               BIGSERIAL           PRIMARY KEY,
    merchant_id      BIGINT              NOT NULL,
    billing_key_id   BIGINT              NOT NULL REFERENCES billing_key(id),
    plan_name        VARCHAR(128)        NOT NULL,
    amount           BIGINT              NOT NULL,
    cycle            VARCHAR(16)         NOT NULL,
    status           subscription_status NOT NULL DEFAULT 'ACTIVE',
    next_charge_date DATE                NOT NULL,
    retry_count      INT                 NOT NULL DEFAULT 0,
    current_period   VARCHAR(8)          NOT NULL,
    created_at       TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP           NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sub_charge ON subscription(status, next_charge_date);

CREATE TABLE billing_charge_log (
    id              BIGSERIAL    PRIMARY KEY,
    subscription_id BIGINT       NOT NULL REFERENCES subscription(id),
    period          VARCHAR(8)   NOT NULL,
    payment_key     VARCHAR(64),
    idempotency_key VARCHAR(128) NOT NULL,
    result          VARCHAR(16)  NOT NULL,
    failure_code    VARCHAR(32),
    attempted_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_charge_log_sub ON billing_charge_log(subscription_id, period);
```

---

## 4. 이벤트 설계

billing-service는 **자체 Kafka 이벤트를 발행하지 않는다.** 청구는 payment-service의 동기 API 호출로 이뤄지고, 결제 성공 시 payment-service가 `payment.paid`를 발행한다. 따라서 정산·알림은 일반 결제와 동일 경로로 합류한다.

> 구독 생성/해지 같은 도메인 이벤트(`subscription.created` 등)는 현재 범위에서 발행하지 않는다. 다른 서비스가 구독 상태를 알 필요가 없기 때문. (확장 시 추가 가능)

---

## 5. API 스펙

> 공통: 응답은 `{ success, data, error }` 래퍼. 금액은 원(KRW) 정수.
> 빌링키 발급·구독 관리는 가맹점(외부) 대상, 청구 호출은 내부.

### POST /v1/billing/keys — 빌링키 발급 (카드 등록)
- 대상: 가맹점(외부). `X-API-KEY` 필요.

**Request**
```json
{
  "customerId": "CUST-77",
  "card": { "number": "1234-5678-9012-3456", "expiry": "12/28", "birthOrBizNo": "990101", "pwd2digit": "12" }
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
> 카드 원문은 PG 빌링키 발급에만 사용하고 저장하지 않는다.

### DELETE /v1/billing/keys/{billingKey} — 빌링키 폐기
- 대상: 가맹점(외부)

**Response (200)**
```json
{ "billingKey": "bky_a9f", "revokedAt": "2026-05-31T11:00:00+09:00" }
```

### POST /v1/billing/subscriptions — 구독 생성
- 대상: 가맹점(외부)

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
  "planName": "프리미엄 월간",
  "amount": 9900,
  "cycle": "MONTHLY",
  "status": "ACTIVE",
  "nextChargeDate": "2026-06-01"
}
```

### DELETE /v1/billing/subscriptions/{id} — 구독 해지
- 대상: 가맹점(외부)

**Response (200)**
```json
{ "subscriptionId": 5001, "status": "CANCELLED", "cancelledAt": "2026-05-31T11:00:00+09:00" }
```

### POST /v1/billing/charge — 수동 즉시 청구 (테스트용)
- 대상: 관리자/내부

**Request**
```json
{ "subscriptionId": 5001 }
```

**Response (200)**
```json
{ "subscriptionId": 5001, "paymentKey": "pay_sub_4e", "amount": 9900, "status": "PAID", "chargedAt": "2026-05-31T11:05:00+09:00" }
```

> 수동 청구도 자동청구와 동일한 멱등키(`구독ID + 주기`)를 사용 → 같은 주기에 자동·수동이 겹쳐도 이중 청구되지 않는다.

---

## 6. 프로세스 & 트랜잭션 경계

### 6.1 빌링키 발급 (동기)

```
가맹점 ──> POST /v1/billing/keys
  ▼
[billing-service]
  │ ① PG 빌링키 발급 호출 (트랜잭션 밖, 카드 원문 전달)
  │ ┌─ 물리 트랜잭션 ────────────────┐
  │ │ ② billing_key 저장 (토큰만)     │
  │ └────────────────────────── 커밋 ┘
  │ ③ 응답 (카드 원문 즉시 폐기)
```

### 6.2 자동청구 배치 (Spring Batch)

```
[billing-service @Scheduled - 매일]
Spring Batch Job
  │ Reader:  status=ACTIVE AND next_charge_date <= 오늘 (페이징, 신규+재시도 통합)
  │ Processor (구독 1건 = 1 트랜잭션):
  │   ① 멱등키 생성 = SUB-{subscriptionId}-{period}
  │   ② payment-service 호출: POST /v1/payments
  │       Header: Idempotency-Key, X-Internal-Service: billing-service, X-Merchant-Id
  │       Body: { method: BILLING, billingKey, amount, orderId: 멱등키, orderName }
  │   ③ 결과 분기:
  │       성공 → next_charge_date = 다음 주기, retry_count = 0, period 갱신
  │       실패 → retry_count++,
  │              retry_count < 3 → next_charge_date = 내일 (재시도)
  │              retry_count >= 3 → status = SUSPENDED
  │   ④ billing_charge_log 기록
  │ Writer:  변경된 subscription 저장 (청크 단위)
  ▼
결제 성공분 → payment-service가 payment.paid 발행 → 정산/알림 합류
```

**트랜잭션 경계 핵심**
- **구독 1건 = 1 트랜잭션** (Spring Batch의 청크를 1로 두거나, 건별 트랜잭션 처리). 한 건 실패가 다른 구독에 영향 없음.
- payment-service 호출(외부)은 트랜잭션 밖. 호출 결과를 받은 뒤 구독 상태를 트랜잭션으로 갱신.
- 배치가 중복 실행돼도 멱등키(`구독ID + 주기`)로 payment-service가 이중 청구를 막음.

### 6.3 단계별 실패 / 서버 다운 복구

| 죽는 시점 | 결과 | 복구 |
|---|---|---|
| 발급 ① PG 호출 후 ~ ② 커밋 전 | PG엔 빌링키 있으나 우리 DB 미저장 | 가맹점이 재발급 요청 (PG는 새 빌링키 발급). 고아 빌링키는 무해(사용 안 하면 그만) |
| 청구 ② payment 호출 후 응답 못 받고 죽음 | 결제는 됐을 수 있으나 우리는 모름 | 다음 배치에서 같은 멱등키로 재호출 → payment-service가 **첫 결과 그대로 반환** (이중 청구 없음). 결과로 구독 상태 정상 갱신 |
| 청구 ③④ 커밋 전 | 구독 상태 미갱신 | 다음 배치 재처리. 멱등키로 안전 |
| 배치 중복 실행 | 같은 구독 동시 처리 위험 | 멱등키로 payment 이중 청구 차단 + (선택) 배치 실행에 분산락 |

> 핵심: 청구의 모든 안전성은 **`구독ID + 주기` 멱등키**에 달려 있다. 서버가 어디서 죽든, 배치가 몇 번 돌든, 같은 주기는 payment-service에서 한 번만 결제된다. billing-service는 "결과를 정확히 반영"하는 것만 재시도로 보장하면 된다.

---

## 7. 트랜잭션 경계 요약

| 작업 | 물리 트랜잭션 | 외부 호출 | 멱등 |
|---|---|---|---|
| 빌링키 발급 | 단일 (billing_key 저장) | PG 발급은 트랜잭션 밖 | - |
| 구독 생성/해지 | 단일 | 없음 | - |
| 자동청구 | 건별 트랜잭션 (구독 상태 갱신 + log) | payment 호출은 트랜잭션 밖 | 구독ID+주기 멱등키 |

> 논리 트랜잭션(청구 → 결제 → 정산)은 이벤트 기반 최종 일관성. billing-service는 "청구를 정확히 한 번 시도(멱등) + 결과를 구독에 반영"까지 책임지고, 결제·정산은 하위 서비스가 책임진다.

---

## 8. 강화 체크리스트 적용 결과

| 강화 항목 | 적용 |
|---|---|
| CloudEvents | 자체 이벤트 발행 없음 (payment.paid로 합류). 해당 약함 |
| Redis 락 TTL | 배치 중복 실행 방지에 분산락 선택 적용 (6.2). 멱등키가 1차 방어라 락은 보조 |
| 트랜잭션 경계 | 외부(PG/payment) 호출은 트랜잭션 밖, 구독 갱신은 건별 트랜잭션 (6장) |
| 단계별 실패 복구 | 죽는 시점별 복구 표 (6.3). 멱등키 기반 재시도로 이중 청구 차단 |
| 컨슈머 재처리 | 소비 이벤트 없음. 대신 청구 실패 재시도(3일/3회)가 동일 개념 |
| 게이트웨이 분리 | 빌링키·구독 API는 가맹점(외부), 청구는 내부/관리자 (5장) |
