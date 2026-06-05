# 정산 서비스 (settlement-service)

> 가맹점별 일일 정산을 담당하는 서비스.
> 결제/취소 이벤트를 받아 자기 DB에 적재해두고, 매일 배치로 집계하여 가맹점에 지급할 금액(payout)을 계산한다.

---

## 1. 책임 범위

| 한다 | 안 한다 |
|---|---|
| 결제/취소 이벤트 수신·적재 | 결제 처리 (payment-service) |
| 일일 정산 집계 배치 (Spring Batch) | 실제 계좌 송금 (범위 외 — 정산 금액 계산까지만) |
| 수수료·지급액 계산 | 가맹점 웹훅 (notification-service) |
| 정산 후 취소 소급(ADJUSTMENT) 처리 | |
| 정산 대사(reconciliation) — 개념·흐름 | 대사 자동 복구 (범위 외) |

> 본 서비스는 "얼마를 지급해야 하는가"를 계산하는 곳이다. 실제 송금 실행은 범위 밖.

---

## 2. 정책

### 2.1 정산 기준

| 항목 | 정책 |
|---|---|
| 주기 | 매일 1회 Spring Batch |
| 기준 시점 | 결제 **승인 시각(`paidAt`)** 기준. 전일 승인된 결제를 당일 정산 |
| 집계 방식 | 이벤트로 미리 적재해둔 정산 항목(settlement_item 후보)을 가맹점별로 집계 |
| 수수료 | 결제액의 2.5% 고정. **건별 계산 후 원 미만 절사**, 그 후 합산 |

> 건별 수수료 계산 이유: 가맹점이 결제 건마다 수수료를 확인할 수 있어야 하고, 특정 건이 취소되면 그 건의 수수료만 정확히 되돌릴 수 있다. 일 합계에 한 번 계산하면 취소 시 역산이 부정확해진다.

### 2.2 정산 항목 타입

| type | 부호 | 의미 |
|---|---|---|
| PAYMENT | + | 정상 결제 (지급 대상) |
| CANCEL | − | 정산 전 취소 (당일 차감) |
| ADJUSTMENT | − | 정산 완료 후 취소된 건의 소급 차감 (다음 정산일 반영) |

### 2.3 데이터 적재 전략 (이벤트 기반)

DB per Service 원칙상 payment DB를 직접 조회하지 않는다. 대신 이벤트를 받아 **자기 DB에 정산 항목 후보로 적재**한다.

- `payment.paid` 수신 → `settlement_item`(type=PAYMENT, status=PENDING) 적재
- `payment.cancelled` 수신 → 해당 결제가 이미 정산됐는지 확인
  - 미정산 → type=CANCEL 적재 (당일 차감)
  - 정산 완료 → type=ADJUSTMENT 적재 (다음 정산일 차감)
- 배치는 적재된 PENDING 항목을 집계하여 정산 확정

> 컨슈머 멱등성: CloudEvents `id` 또는 `paymentKey + 이벤트종류`로 중복 적재 방지.

### 2.4 정산 후 취소 (소급)

이미 정산이 끝난 결제가 취소되면 그 정산은 이미 지급 계산이 끝났으므로 되돌리지 않는다. 대신:
- `payment.cancelled` 수신 시 해당 결제의 정산 여부 확인
- 이미 정산됨 → `ADJUSTMENT`(음수) 항목으로 적재 → **다음 정산일**에 그 가맹점 정산금에서 차감(상계)

> 토스페이먼츠도 "이미 정산받은 결제도 취소 가능하며 다음 정산금에서 상계처리"한다고 안내한다. 동일한 방식.

### 2.5 정산 대사 (reconciliation) — 개념·흐름

정산 대사는 **외부(PG·은행)의 거래 내역과 우리 시스템의 정산 데이터를 대조해 불일치를 찾는 절차**다. 본 프로젝트는 개념과 흐름만 설계하고, 자동 복구 로직은 범위 밖으로 둔다.

**왜 어려운가 (정직하게)**
- **시점 차이**: PG는 승인/취소를 실시간 반영하지만, 은행 실제 입출금·정산 반영은 영업일 기준 며칠 늦는다. "같은 거래"인데 시스템마다 반영 시점이 달라 단순 비교가 안 된다.
- **부분취소·소급취소**: 한 결제가 여러 번 부분취소되고, 정산 후 취소가 소급되면 한 거래가 여러 정산일에 흩어진다. 1:1 매칭이 깨진다.
- **상태 불일치**: 우리는 미취소인데 PG는 취소됨(취소 콜백 유실), 또는 그 반대. 어느 쪽이 진실인지 판단 기준이 필요하다.

**불일치 유형 (분류만)**
| 유형 | 설명 | 대응 방향 (개념) |
|---|---|---|
| 누락 | PG엔 있는데 우리 정산 항목에 없음 | 이벤트 유실 의심 → 항목 보정 적재 |
| 과다 | 우리엔 있는데 PG엔 없음 | 잘못 적재/중복 → 제거 검토 |
| 금액 불일치 | 양쪽 있으나 금액 다름 | 부분취소 누락 등 → 재계산 |
| 상태 불일치 | 취소 여부가 다름 | PG를 신뢰 기준으로 보정 (일반적으로 PG가 source of truth) |

**흐름 (개념)**
```
[reconciliation 배치 - 일 1회, 정산 후]
  │ ① PG 거래내역 조회 (Mock PG의 일별 거래 목록)
  │ ② 우리 settlement_item과 대조 (paymentKey/pgTid 기준)
  │ ③ 불일치 발견 시 reconciliation_log에 기록
  │ ④ (범위 밖) 자동 보정 또는 운영자 알림
```

> 포트폴리오에서는 "대사가 필요하다는 것을 알고, 불일치 유형을 분류하고, 어디까지 자동화할지 선을 그었다"까지가 목표. 자동 복구는 의도적으로 범위에서 제외.

---

## 3. ERD

```
settlement (1) ──< (N) settlement_item
(별도) reconciliation_log
```

### settlement (정산 헤더)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| merchant_id | BIGINT | 가맹점 ID |
| settlement_date | DATE | 정산 기준일 |
| total_amount | BIGINT | 결제 합계 |
| cancel_amount | BIGINT | 취소 차감 합계 (CANCEL+ADJUSTMENT) |
| fee_amount | BIGINT | 수수료 합계 |
| payout_amount | BIGINT | 실지급액 (total − cancel − fee) |
| status | settlement_status | PENDING / COMPLETED |
| created_at | TIMESTAMP | |
| | | `unique(merchant_id, settlement_date)` |

### settlement_item (정산 상세)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| settlement_id | BIGINT FK | 확정 시 연결 (적재 시점엔 NULL 가능) |
| merchant_id | BIGINT | 가맹점 ID |
| payment_key | VARCHAR(64) | 결제키 |
| type | item_type | PAYMENT / CANCEL / ADJUSTMENT |
| amount | BIGINT | 금액 (CANCEL/ADJUSTMENT는 음수) |
| fee_amount | BIGINT | 건별 수수료 (음수 항목은 음수) |
| payout_amount | BIGINT | amount − fee |
| target_date | DATE | 이 항목이 반영될 정산일 |
| status | item_status | PENDING / SETTLED |
| event_id | VARCHAR(64) | 멱등키 (CloudEvents id) |
| created_at | TIMESTAMP | |

### reconciliation_log (대사 결과 — 개념)
recon_date, payment_key, mismatch_type(MISSING/EXTRA/AMOUNT/STATUS), pg_value, our_value, resolved, created_at.

```sql
CREATE TYPE settlement_status AS ENUM ('PENDING', 'COMPLETED');
CREATE TYPE item_type AS ENUM ('PAYMENT', 'CANCEL', 'ADJUSTMENT');
CREATE TYPE item_status AS ENUM ('PENDING', 'SETTLED');

CREATE TABLE settlement (
    id              BIGSERIAL          PRIMARY KEY,
    merchant_id     BIGINT             NOT NULL,
    settlement_date DATE               NOT NULL,
    total_amount    BIGINT             NOT NULL DEFAULT 0,
    cancel_amount   BIGINT             NOT NULL DEFAULT 0,
    fee_amount      BIGINT             NOT NULL DEFAULT 0,
    payout_amount   BIGINT             NOT NULL DEFAULT 0,
    status          settlement_status  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP          NOT NULL DEFAULT NOW(),
    UNIQUE (merchant_id, settlement_date)
);

CREATE TABLE settlement_item (
    id            BIGSERIAL    PRIMARY KEY,
    settlement_id BIGINT       REFERENCES settlement(id),
    merchant_id   BIGINT       NOT NULL,
    payment_key   VARCHAR(64)  NOT NULL,
    type          item_type    NOT NULL,
    amount        BIGINT       NOT NULL,
    fee_amount    BIGINT       NOT NULL,
    payout_amount BIGINT       NOT NULL,
    target_date   DATE         NOT NULL,
    status        item_status  NOT NULL DEFAULT 'PENDING',
    event_id      VARCHAR(64)  NOT NULL UNIQUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_item_target ON settlement_item(merchant_id, target_date, status);
```

> `event_id` UNIQUE → 같은 이벤트 중복 수신 시 적재 중복 차단 (컨슈머 멱등).

---

## 4. 이벤트 (소비)

settlement-service는 이벤트를 **소비만** 한다 (발행 없음).

| 토픽 | 동작 |
|---|---|
| payment.paid | settlement_item(PAYMENT) 적재 |
| payment.cancelled | 정산 여부 확인 → CANCEL 또는 ADJUSTMENT 적재 |

### 컨슈머 처리 실패 / 재처리

| 단계 | 실패 시 |
|---|---|
| 이벤트 수신 후 적재 실패 (DB 오류 등) | Kafka 오프셋 커밋 안 함 → 재consume (at-least-once). `event_id` UNIQUE로 중복 적재 방지 |
| 반복 실패 | 재시도 한도 초과 시 **DLQ(Dead Letter Queue)** 또는 `failed_event` 테이블로 격리 → 운영자 확인 후 재처리 |
| 처리 자체는 성공인데 같은 이벤트 또 옴 | `event_id` 중복 → 무시 (멱등) |

> 발행 측(payment-service)이 at-least-once로 보내므로, 소비 측은 **멱등 + 재처리 + DLQ**로 받는다. 이 조합이 정산 정확성의 핵심.

---

## 5. API 스펙

> 공통: 응답은 `{ success, data, error }` 래퍼. 금액은 원(KRW) 정수.

### GET /v1/settlements — 정산 내역 조회
- 대상: 가맹점(외부) / 관리자
- Query: `settlementDate`, `from`, `to` (merchantId는 인증에서 추출)

**Response (200)**
```json
{
  "content": [
    {
      "settlementId": 7001,
      "settlementDate": "2026-05-31",
      "totalAmount": 1500000,
      "cancelAmount": 50000,
      "feeAmount": 36250,
      "payoutAmount": 1413750,
      "status": "COMPLETED"
    }
  ]
}
```

### GET /v1/settlements/{id} — 정산 상세
- 대상: 가맹점(외부) / 관리자

**Response (200)**
```json
{
  "settlementId": 7001,
  "merchantId": 1001,
  "settlementDate": "2026-05-31",
  "totalAmount": 1500000,
  "cancelAmount": 50000,
  "feeRate": 0.025,
  "feeAmount": 36250,
  "payoutAmount": 1413750,
  "status": "COMPLETED",
  "items": [
    { "paymentKey": "pay_7f3a9b", "type": "PAYMENT", "amount": 50000, "feeAmount": 1250, "payoutAmount": 48750 },
    { "paymentKey": "pay_3a1c", "type": "CANCEL", "amount": -20000, "feeAmount": -500, "payoutAmount": -19500 },
    { "paymentKey": "pay_9f2d", "type": "ADJUSTMENT", "amount": -30000, "feeAmount": -750, "payoutAmount": -29250 }
  ]
}
```

### POST /v1/settlements/run — 정산 배치 수동 실행
- 대상: 관리자/내부. `X-Internal-Service` 또는 관리자 권한.

**Request**
```json
{ "settlementDate": "2026-05-31" }
```

**Response (202)**
```json
{ "jobId": "settlement-job-20260531", "settlementDate": "2026-05-31", "status": "RUNNING" }
```

---

## 6. 프로세스 & 트랜잭션 경계

### 6.1 이벤트 적재 (실시간)

```
Kafka payment.paid ──> [settlement-service consumer]
  │ ① event_id 중복 확인 (UNIQUE)
  │ ② 건별 수수료 계산 (amount * 2.5%, 원 미만 절사)
  │ ③ settlement_item(PAYMENT, PENDING, target_date=승인일) 적재
  │ ④ Kafka 오프셋 커밋
```
```
Kafka payment.cancelled ──> [consumer]
  │ ① event_id 중복 확인
  │ ② 해당 paymentKey의 정산 여부 확인
  │     미정산 → settlement_item(CANCEL, target_date=취소일)
  │     정산됨 → settlement_item(ADJUSTMENT, target_date=다음 정산일)
  │ ③ 오프셋 커밋
```

### 6.2 정산 집계 배치 (Spring Batch)

```
[settlement-service @Scheduled - 매일]
Spring Batch Job (settlement_date = 전일)
  │ Reader:  target_date = 기준일 AND status=PENDING 인 settlement_item (가맹점별 정렬)
  │ Processor (가맹점 단위 집계):
  │   ① PAYMENT 합계, CANCEL/ADJUSTMENT 차감 합계
  │   ② fee 합계, payout = total − cancel − fee
  │ Writer (가맹점 1건 = 1 트랜잭션):
  │   ③ settlement 헤더 생성 (COMPLETED)
  │   ④ 관련 settlement_item을 SETTLED로 + settlement_id 연결
  ▼
정산 완료
```

**트랜잭션 경계 핵심**
- **가맹점 1건 = 1 트랜잭션**. 헤더 생성 + 항목 SETTLED 전이를 원자적으로. 한 가맹점 실패가 다른 가맹점 정산을 막지 않음.
- 적재(6.1)와 집계(6.2)를 분리 → 집계는 순수하게 자기 DB만 읽고 쓴다(외부 호출 없음). 빠르고 안전.

### 6.3 단계별 실패 / 서버 다운 복구

| 죽는 시점 | 결과 | 복구 |
|---|---|---|
| 이벤트 적재 전 | Kafka 오프셋 미커밋 | 재consume (event_id 멱등) |
| 적재 후 ~ 오프셋 커밋 전 | 적재됨, 재consume 가능 | event_id UNIQUE로 중복 무시 |
| 집계 배치 중단 (일부 가맹점만 완료) | 완료분은 COMPLETED, 미완료분은 PENDING 유지 | 배치 재실행 시 PENDING만 다시 집계 (이미 SETTLED는 제외) |
| 집계 후 정산 데이터 의심 | — | reconciliation 배치가 PG와 대조해 불일치 탐지 (2.5) |

> 멱등 키(`event_id`)와 항목 상태(PENDING/SETTLED) 덕분에, 적재든 집계든 중복 실행해도 결과가 틀어지지 않는다.

---

## 7. 트랜잭션 경계 요약

| 작업 | 물리 트랜잭션 | 외부 호출 | 멱등 |
|---|---|---|---|
| 이벤트 적재 | 단일 (item 적재) | 없음 (수신측) | event_id UNIQUE |
| 정산 집계 | 가맹점별 트랜잭션 (헤더+항목 전이) | 없음 | 항목 status PENDING→SETTLED |
| 대사 | (범위 밖) | PG 거래내역 조회 | — |

> 논리 트랜잭션: 결제→정산은 이벤트 기반 최종 일관성. 정산 정확성은 "at-least-once 수신 + event_id 멱등 + 상태 기반 집계"로 보장.

---

## 8. 강화 체크리스트 적용 결과

| 강화 항목 | 적용 |
|---|---|
| CloudEvents | 소비 측. CloudEvents id를 멱등키(event_id)로 사용 (3장, 4장) |
| Redis 락 TTL | 직접 락 없음. 배치 중복 실행은 항목 상태(PENDING/SETTLED)로 방어 |
| 트랜잭션 경계 | 적재/집계 분리, 가맹점별 트랜잭션 (6장) |
| 단계별 실패 복구 | 죽는 시점별 복구 표 + 상태 기반 재집계 (6.3) |
| 컨슈머 재처리 | **이 서비스의 핵심.** at-least-once + event_id 멱등 + DLQ (4장) |
| 게이트웨이 분리 | 조회는 가맹점(외부), 배치 실행은 관리자/내부 (5장) |
| 정산 대사 | 개념·흐름·불일치 유형 설계, 자동 복구는 범위 외로 명시 (2.5) |
