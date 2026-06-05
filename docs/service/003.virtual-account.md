# 가상계좌 서비스 (virtual-account-service)

> 가상계좌 채번(발급)과 입금 콜백 처리를 담당하는 서비스.
> 결제 레코드는 소유하지 않는다 — payment-service가 결제의 단일 소유자이고, 본 서비스는 계좌·입금만 책임진다 (A안).

---

## 1. 책임 범위

| 한다 | 안 한다 |
|---|---|
| 가상계좌 채번 요청 (Mock 은행에) | 결제 레코드 생성·상태 전이 (payment-service) |
| 입금 콜백 수신·검증 | 결제 PAID/FAILED 전이 (payment-service가 이벤트 받아서) |
| 입금/만료 이벤트 발행 (va.deposited / va.expired) | 가맹점 웹훅 전송 (notification-service) |
| 가상계좌 상태 관리 (대기/입금/만료) | 정산 (settlement-service) |

> 핵심 경계: 본 서비스는 "이 계좌에 정확한 금액이 입금됐다 / 만료됐다"는 **사실만** 이벤트로 알린다. 그 사실로 결제를 어떻게 처리할지는 payment-service가 결정한다.

---

## 2. 정책

### 2.1 가상계좌 상태 (VirtualAccountStatus)

| 상태 | 의미 |
|---|---|
| WAITING_DEPOSIT | 계좌 발급됨, 입금 대기 |
| DEPOSITED | 정확한 금액 입금 완료 |
| EXPIRED | 입금 기한 초과 |

> 결제 상태(PaymentStatus)와는 별개. 한 쌍으로 움직인다: 발급 시 Payment=READY + VA=WAITING_DEPOSIT, 입금 시 Payment=PAID + VA=DEPOSITED, 만료 시 Payment=FAILED + VA=EXPIRED.

### 2.2 채번 (계좌번호 발급)

| 항목 | 정책 |
|---|---|
| 발급 주체 | 실무처럼 **은행(Mock 은행)이 채번**한다. 본 서비스가 직접 번호를 만들지 않고 Mock 은행에 요청해서 받는다 |
| 트리거 | payment-service가 `POST /internal/virtual-accounts`로 발급 요청 (가맹점이 직접 호출하지 않음) |
| 입금 기한 | 기본 24시간. 발급 시 `expiresAt` 설정 |

### 2.3 입금 금액 처리

| 항목 | 정책 |
|---|---|
| 일치 | 발급 금액과 입금 금액이 **정확히 일치할 때만** 입금 인정 → DEPOSITED |
| 불일치 | 과입금·부족입금 모두 **입금 인정하지 않음**. 사유를 응답/기록만 하고 상태는 WAITING_DEPOSIT 유지 |
| 멱등성 | 동일 입금 콜백 중복 수신 시 1회만 처리 (`bankTxId` 기준) |

> 불일치 시 환불·차액 처리 같은 복잡한 흐름은 범위에서 제외. "정확히 일치만 인정, 아니면 사유 응답"으로 단순화.

### 2.4 만료 vs 입금 경쟁 상태 (입금 우선)

만료 처리 시점과 입금이 거의 동시에 일어날 수 있다. **입금을 우선**한다.

- 입금 콜백 처리와 만료 배치는 **같은 가상계좌 레코드에 대해 Redis 락 + 상태 검증**으로 직렬화한다.
- 만료 배치가 락을 잡고 `WAITING_DEPOSIT`을 확인한 뒤에만 EXPIRED로 전이한다. 그 사이 입금이 먼저 DEPOSITED로 바꿨다면 만료는 스킵.
- 반대로 입금 콜백이 락을 잡았을 때 이미 EXPIRED라면? → **입금 우선 정책**상, 만료를 되돌려 입금을 인정하지 않고(이미 만료 이벤트가 나갔을 수 있으므로) **보정 절차로 처리**한다. (아래 2.5)

> 락 키: `lock:va:{virtualAccountNo}`, TTL 10초. 입금 콜백·만료 배치 양쪽이 동일 키로 경쟁.

### 2.5 입금 우선 보정 (만료 이벤트가 이미 나간 경우)

만료 이벤트(`va.expired`)가 발행되어 payment-service가 이미 FAILED 처리했는데, 직후 정상 입금이 확인되는 드문 경우:

- 입금이 기한 **직전**에 일어났다면(은행 통보 지연) 입금을 인정하고 `va.deposited`를 발행한다.
- payment-service는 `FAILED → PAID`로 직접 전이할 수 없으므로(상태 전이 정책), 이 케이스는 **reconciliation(정산 대사) 배치가 잡아 수동/보정 처리**하도록 기록을 남긴다.
- 즉, 일반 경로에서는 락으로 경쟁을 막고, 락으로도 못 막은 극단 케이스는 대사로 잡는 2단 방어.

> 포트폴리오 범위에서는 "락으로 1차 방어, 대사로 2차 안전망"까지만 설계하고 자동 복구 구현은 생략 가능. 면접에서 경쟁 상태 인지와 해결 접근을 보여주는 게 목적.

### 2.6 입금 콜백 인증 (HMAC 서명)

Mock 은행 → 본 서비스 입금 콜백은 위변조·위조 호출을 막기 위해 HMAC 서명으로 검증한다. (토스페이먼츠 웹훅 검증과 동일 방식)

| 항목 | 정책 |
|---|---|
| 서명 생성 | Mock 은행이 `{body}:{timestamp}`를 사전 공유한 secret으로 HMAC SHA-256 |
| 전달 | `X-Bank-Signature: v1:{base64}`, `X-Bank-Timestamp` 헤더 |
| 검증 | 본 서비스가 동일하게 해싱하여 일치 확인. 불일치 시 401 |
| 재전송 공격 방지 | timestamp가 허용 시간(예: 5분) 벗어나면 거부 |

---

## 3. ERD

```
virtual_account (1) ──< (N) deposit_log
virtual_account (1) ──< (N) outbox_event  (논리적; aggregate_id로 참조)
```

### virtual_account

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| payment_key | VARCHAR(64) UNIQUE | payment-service의 결제키 (연결 고리) |
| merchant_id | BIGINT | 가맹점 ID |
| virtual_account_no | VARCHAR(32) UNIQUE | Mock 은행이 채번한 계좌번호 |
| bank_code | VARCHAR(8) | 은행 코드 |
| amount | BIGINT | 입금 받아야 할 금액 |
| depositor_name | VARCHAR(64) | 입금자명 |
| status | va_status | WAITING_DEPOSIT / DEPOSITED / EXPIRED |
| expires_at | TIMESTAMP | 입금 기한 |
| deposited_at | TIMESTAMP | 입금 확정 시각 |
| created_at / updated_at | TIMESTAMP | |

### deposit_log
입금 콜백 수신 이력 (멱등성·감사). bank_tx_id (UNIQUE), virtual_account_id, deposit_amount, result(ACCEPTED/MISMATCH/DUPLICATE), reason, received_at.

> `bank_tx_id`에 UNIQUE 제약 → 동일 콜백 중복 수신 시 INSERT 충돌로 중복 차단.

### outbox_event
payment-service와 동일 구조. va.deposited / va.expired 이벤트를 PENDING으로 저장 후 별도 퍼블리셔가 Kafka 발행.

```sql
CREATE TYPE va_status AS ENUM ('WAITING_DEPOSIT', 'DEPOSITED', 'EXPIRED');

CREATE TABLE virtual_account (
    id                 BIGSERIAL   PRIMARY KEY,
    payment_key        VARCHAR(64) NOT NULL UNIQUE,
    merchant_id        BIGINT      NOT NULL,
    virtual_account_no VARCHAR(32) NOT NULL UNIQUE,
    bank_code          VARCHAR(8)  NOT NULL,
    amount             BIGINT      NOT NULL,
    depositor_name     VARCHAR(64),
    status             va_status   NOT NULL DEFAULT 'WAITING_DEPOSIT',
    expires_at         TIMESTAMP   NOT NULL,
    deposited_at       TIMESTAMP,
    created_at         TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE deposit_log (
    id                 BIGSERIAL    PRIMARY KEY,
    bank_tx_id         VARCHAR(64)  NOT NULL UNIQUE,
    virtual_account_id BIGINT       NOT NULL REFERENCES virtual_account(id),
    deposit_amount     BIGINT       NOT NULL,
    result             VARCHAR(16)  NOT NULL,
    reason             VARCHAR(128),
    received_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_va_status_expires ON virtual_account(status, expires_at);
```

---

## 4. 이벤트 설계 (CloudEvents)

payment-service와 동일하게 CloudEvents 1.0 structured mode. `subject`는 paymentKey.

| 토픽 | 프로듀서 | 컨슈머 |
|---|---|---|
| va.deposited | virtual-account-service | payment-service |
| va.expired | virtual-account-service | payment-service |

**va.deposited `data`**
```json
{
  "paymentKey": "pay_va_8c",
  "virtualAccountNo": "88812345678901",
  "merchantId": 1001,
  "amount": 30000,
  "depositedAt": "2026-05-31T16:10:00+09:00"
}
```

**va.expired `data`**
```json
{
  "paymentKey": "pay_va_8c",
  "virtualAccountNo": "88812345678901",
  "merchantId": 1001,
  "expiredAt": "2026-06-01T14:30:00+09:00"
}
```

> 두 이벤트 모두 payment-service만 소비한다. payment-service가 결제 상태를 전이한 뒤, 알림이 필요하면 그쪽에서 payment.paid를 발행한다 (가상계좌 입금도 결국 결제 완료 알림으로 통합 — 알림 중복 방지).

---

## 5. API 스펙

> 공통: 응답은 `{ success, data, error }` 래퍼. 금액은 원(KRW) 정수.
> 본 서비스의 API는 **외부 가맹점에 직접 노출되지 않는다.** 발급은 내부(payment-service), 입금 콜백은 Mock 은행 전용. (게이트웨이 분리 — 가맹점용 게이트웨이에는 이 API들이 라우팅되지 않음)

### POST /internal/virtual-accounts — 계좌 발급
- 대상: payment-service (내부). `X-Internal-Service` 필요.

**Request**
```json
{
  "paymentKey": "pay_va_8c",
  "merchantId": 1001,
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
> 내부적으로 Mock 은행 채번 API를 호출해 계좌번호를 받아온다.

### POST /v1/virtual-accounts/deposit-callback — 입금 콜백
- 대상: Mock 은행. HMAC 서명 헤더 필수.
- Header: `X-Bank-Signature`, `X-Bank-Timestamp`

**Request**
```json
{
  "virtualAccountNo": "88812345678901",
  "depositAmount": 30000,
  "depositedAt": "2026-05-31T16:10:00+09:00",
  "bankTxId": "BANK-TX-9981"
}
```

**Response (200) — 인정**
```json
{ "result": "ACCEPTED", "paymentKey": "pay_va_8c", "status": "DEPOSITED" }
```

**Response (200) — 금액 불일치 (인정 안 함)**
```json
{ "result": "MISMATCH", "reason": "AMOUNT_NOT_MATCHED", "expected": 30000, "received": 25000 }
```

**Response (200) — 중복 콜백**
```json
{ "result": "DUPLICATE", "bankTxId": "BANK-TX-9981" }
```

### GET /v1/virtual-accounts/{paymentKey} — 가상계좌 조회
- 대상: 내부/관리자

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

---

## 6. 프로세스 & 트랜잭션 경계

### 6.1 계좌 발급 (동기)

```
payment-service ──> POST /internal/virtual-accounts
  ▼
[virtual-account-service]
  │ ① Mock 은행 채번 호출 (트랜잭션 밖, 외부 호출)
  │ ┌─ 물리 트랜잭션 ──────────────────┐
  │ │ ② virtual_account 저장 (WAITING) │
  │ └────────────────────────── 커밋 ┘
  │ ③ 계좌 정보 응답 (동기 종료)
```

### 6.2 입금 콜백 (비동기 수신, Outbox 적용)

```
Mock 은행 ──> POST /v1/virtual-accounts/deposit-callback (HMAC 서명)
  ▼
[virtual-account-service]
  │ ① HMAC 서명 + timestamp 검증 (실패 → 401)
  │ ② Redis 락 lock:va:{virtualAccountNo} (TTL 10s)
  │ ③ bank_tx_id 중복 확인 (deposit_log) → 있으면 DUPLICATE 응답
  │ ④ 금액 일치 검증 → 불일치면 deposit_log(MISMATCH) 기록 후 응답 (상태 유지)
  │ ┌─ 물리 트랜잭션 ───────────────────────┐
  │ │ ⑤ deposit_log(ACCEPTED) 기록           │
  │ │ ⑥ virtual_account DEPOSITED 전이        │
  │ │ ⑦ outbox_event(va.deposited, PENDING)   │
  │ └────────────────────────────── 커밋 ┘
  │ ⑧ 락 해제, ACCEPTED 응답
  ▼
[Outbox Publisher] → Kafka va.deposited → payment-service (결제 PAID 전이)
```

### 6.3 만료 (스케줄러, 입금 우선)

```
[virtual-account-service @Scheduled]
  │ status=WAITING_DEPOSIT AND expires_at < now 조회
  │ 각 건마다:
  │   ① Redis 락 lock:va:{virtualAccountNo}
  │   ② 상태 재확인 (락 잡은 사이 입금됐을 수 있음)
  │       → DEPOSITED면 스킵 (입금 우선)
  │   ┌─ 트랜잭션 ────────────────────────┐
  │   │ ③ EXPIRED 전이                      │
  │   │ ④ outbox_event(va.expired, PENDING) │
  │   └──────────────────────────── 커밋 ┘
  │   ⑤ 락 해제
  ▼
Kafka va.expired → payment-service (결제 FAILED 전이, failureCode=VA_EXPIRED)
```

### 6.4 단계별 실패 / 서버 다운 복구

| 죽는 시점 | 결과 | 복구 |
|---|---|---|
| 발급 ① 채번 후 ~ ② 커밋 전 | Mock 은행엔 계좌 있을 수 있으나 우리 DB 미저장 | payment-service가 발급 응답 못 받음 → 결제 READY 유지, 재요청/타임아웃 복구(payment의 verify) |
| 입금 ②~④ | 트랜잭션 전이라 미반영 | 은행이 콜백 재전송 (대부분 PG/은행은 콜백 재시도함). bank_tx_id로 멱등 |
| 입금 ⑤~⑦ 커밋 전 | 롤백, WAITING 유지 | 콜백 재전송으로 복구 |
| 입금 ⑦ 커밋 후 ~ 발행 전 | DEPOSITED, outbox=PENDING | Outbox Publisher가 발행 (유실 없음) |
| 만료 ③④ 커밋 전 | WAITING 유지 | 다음 스케줄 주기에 재처리 |
| 입금/만료 경쟁 | 락으로 직렬화, 상태 재확인으로 방지 | 락으로도 못 막은 극단 케이스는 reconciliation 대사로 보정 (2.5) |

> 은행 콜백은 순서·도달이 보장되지 않는다(실무 PG도 동일). 그래서 **멱등(bank_tx_id) + 상태 기반 처리 + Outbox**로 "여러 번 와도, 늦게 와도, 순서가 바뀌어도" 안전하게 만든다.

---

## 7. 트랜잭션 경계 요약

| 작업 | 물리 트랜잭션 | 외부 호출 | 이벤트 |
|---|---|---|---|
| 계좌 발급 | 단일 (va 저장) | Mock 은행 채번은 트랜잭션 밖 | 없음 |
| 입금 콜백 | 단일 (log+상태+outbox) | 없음 (수신측) | va.deposited (outbox) |
| 만료 | 단일 (상태+outbox), 건별 | 없음 | va.expired (outbox) |

> 논리 트랜잭션(입금 → 결제 PAID)은 단일 DB 트랜잭션이 아니라 이벤트 기반 최종 일관성. 본 서비스는 "입금 사실 확정 + 이벤트 발행 보장"까지 책임지고, 결제 전이는 payment-service가 책임진다.

---

## 8. 강화 체크리스트 적용 결과

| 강화 항목 | 적용 |
|---|---|
| CloudEvents | va.deposited / va.expired를 CloudEvents 1.0으로 발행 (4장) |
| Redis 락 TTL | 입금/만료 경쟁 직렬화에 분산락 + TTL 10s (2.4, 6.3) |
| 트랜잭션 경계 | 외부 채번 호출은 트랜잭션 밖, 이벤트는 Outbox (6장) |
| 단계별 실패 복구 | 죽는 시점별 복구 표 + 콜백 재전송 + Outbox + 대사 (6.4) |
| 컨슈머 재처리 | 발행 보장은 여기서. 소비측(payment-service)이 결제 전이 실패 시 재처리 |
| 게이트웨이 분리 | 모든 API가 내부/은행 전용 — 가맹점 게이트웨이 비노출 (5장) |
| 콜백 보안 | HMAC SHA-256 서명 + timestamp 재전송 방지 (2.6) |
