# 알림 서비스 (notification-service)

> 결제/취소 결과를 가맹점 웹훅으로 전송하는 서비스.
> 결제 이벤트를 소비해서 가맹점이 등록한 URL로 통지한다. 전송 실패는 제한적으로 재시도하고, 끝내 실패하면 기록만 남긴다.

---

## 1. 책임 범위

| 한다 | 안 한다 |
|---|---|
| payment.paid / payment.cancelled 소비 | 결제 처리 (payment-service) |
| 가맹점 웹훅 전송 (HMAC 서명) | 정산 (settlement-service) |
| 전송 실패 재시도 (최대 3회) | 실패분 자동 재발송 API (범위 외 — 기록만) |
| 전송 이력 기록 | 가맹점 웹훅 URL 관리 (merchant-service) |

> 알림은 결제 흐름의 곁가지다. 알림이 실패해도 결제·정산에는 영향을 주지 않는다(장애 격리). 그래서 완결성보다 "결제 흐름을 방해하지 않는 것"이 우선이다.

---

## 2. 정책

### 2.1 소비 대상

| 토픽 | 통지 이벤트 타입 |
|---|---|
| payment.paid | PAYMENT_PAID |
| payment.cancelled | PAYMENT_CANCELLED |

> `va.deposited` / `va.expired`는 직접 소비하지 않는다. 가상계좌 입금도 payment-service가 payment.paid로 재발행하므로 그 경로로 알림이 나간다 (알림 중복 방지).

### 2.2 웹훅 전송

| 항목 | 정책 |
|---|---|
| 대상 URL | merchant-service에 등록된 가맹점 webhook_url (이벤트의 merchantId로 조회) |
| 방식 | HTTP POST, 본문은 결제 결과 JSON |
| 서명 | `{body}:{timestamp}`를 가맹점별 secret으로 HMAC SHA-256 → `X-Webhook-Signature` 헤더 (가맹점이 위변조 검증) |
| 성공 기준 | 가맹점 서버가 2xx 응답 |
| 타임아웃 | 5초 |

### 2.3 재시도

| 항목 | 정책 |
|---|---|
| 횟수 | 최대 3회 (지수 백오프, 예: 1m → 3m → 5m) |
| 실패 처리 | 3회 모두 실패 시 `webhook_delivery`를 FAILED로 남기고 **종료**. 자동 재발송은 하지 않음 |
| 후속 | 실패 기록은 보존. 운영자가 나중에 수동으로 확인/처리 (자동화는 범위 밖) |

> 재시도를 3회로 제한하고 자동 재발송을 두지 않는 이유: 알림은 결제 성공의 필수 조건이 아니다. 가맹점은 웹훅을 못 받아도 결제 조회 API(`GET /v1/payments/{paymentKey}`)로 결과를 확인할 수 있다. 따라서 알림은 "합리적 수준까지만 시도"하고 실패는 기록으로 남긴다.

---

## 3. ERD

```
webhook_delivery (전송 시도/상태 기록)
```

### webhook_delivery

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| event_id | VARCHAR(64) UNIQUE | 소비한 CloudEvents id (멱등키) |
| merchant_id | BIGINT | 가맹점 ID |
| event_type | VARCHAR(32) | PAYMENT_PAID / PAYMENT_CANCELLED |
| payment_key | VARCHAR(64) | 결제키 |
| webhook_url | VARCHAR(512) | 전송 대상 URL (전송 시점 스냅샷) |
| payload | JSONB | 전송 본문 |
| status | delivery_status | PENDING / SUCCESS / FAILED |
| attempt_count | INT | 시도 횟수 |
| next_attempt_at | TIMESTAMP | 다음 재시도 예정 시각 |
| last_error | VARCHAR(256) | 마지막 실패 사유 |
| created_at / updated_at | TIMESTAMP | |

```sql
CREATE TYPE delivery_status AS ENUM ('PENDING', 'SUCCESS', 'FAILED');

CREATE TABLE webhook_delivery (
    id              BIGSERIAL        PRIMARY KEY,
    event_id        VARCHAR(64)      NOT NULL UNIQUE,
    merchant_id     BIGINT           NOT NULL,
    event_type      VARCHAR(32)      NOT NULL,
    payment_key     VARCHAR(64)      NOT NULL,
    webhook_url     VARCHAR(512)     NOT NULL,
    payload         JSONB            NOT NULL,
    status          delivery_status  NOT NULL DEFAULT 'PENDING',
    attempt_count   INT              NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    last_error      VARCHAR(256),
    created_at      TIMESTAMP        NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP        NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_delivery_retry ON webhook_delivery(status, next_attempt_at);
```

> `event_id` UNIQUE → 같은 이벤트 중복 수신 시 전송 레코드 중복 생성 차단 (컨슈머 멱등).

---

## 4. 이벤트 (소비)

notification-service는 이벤트를 **소비만** 한다 (발행 없음).

| 토픽 | 동작 |
|---|---|
| payment.paid | webhook_delivery(PENDING) 생성 → 전송 시도 |
| payment.cancelled | webhook_delivery(PENDING) 생성 → 전송 시도 |

### 컨슈머 처리 실패 / 재처리

| 단계 | 실패 시 |
|---|---|
| 이벤트 수신 후 delivery 생성 실패 | Kafka 오프셋 미커밋 → 재consume. event_id UNIQUE로 중복 생성 방지 |
| 같은 이벤트 또 옴 | event_id 중복 → 무시 (멱등) |

> 여기서 "재시도"는 두 종류로 구분된다. (1) **이벤트 소비 실패** → Kafka 재consume (멱등으로 안전). (2) **웹훅 전송 실패** → webhook_delivery 기반 자체 재시도(최대 3회). 둘은 별개 메커니즘이다.

---

## 5. API 스펙

> 알림 서비스는 외부 API가 거의 없다. 전송 이력 조회 정도만 둔다.

### GET /v1/webhook-deliveries — 전송 이력 조회
- 대상: 가맹점(외부) / 관리자
- Query: `paymentKey`, `status`, `from`, `to`

**Response (200)**
```json
{
  "content": [
    {
      "eventType": "PAYMENT_PAID",
      "paymentKey": "pay_7f3a9b",
      "status": "FAILED",
      "attemptCount": 3,
      "lastError": "connection timeout",
      "createdAt": "2026-05-31T14:23:05+09:00"
    }
  ]
}
```

> 가맹점은 이 API로 "웹훅을 못 받은 건"을 확인할 수 있다. (수동 재발송 API는 범위 밖)

---

## 6. 프로세스 & 트랜잭션 경계

### 6.1 웹훅 전송 (이벤트 수신 → 전송)

```
Kafka payment.paid ──> [notification-service consumer]
  │ ① event_id 중복 확인 (UNIQUE)
  │ ② merchant-service에서 webhook_url 조회 (동기)
  │ ③ webhook_delivery(PENDING) 생성
  │ ④ Kafka 오프셋 커밋
  ▼
[전송 워커 / 스케줄러]
  │ ⑤ PENDING 또는 재시도 대상(next_attempt_at <= now) 조회
  │ ⑥ HMAC 서명 후 가맹점 URL로 POST (timeout 5s)
  │ ⑦ 결과 분기:
  │     2xx → status=SUCCESS
  │     실패 → attempt_count++,
  │             < 3 → next_attempt_at = 백오프 후 시각 (재시도 대기)
  │             >= 3 → status=FAILED (종료, 기록만)
```

**트랜잭션 경계**
- 이벤트 소비(①~④)와 실제 전송(⑤~⑦)을 **분리**한다. 소비 시점엔 delivery 레코드만 만들고, 전송은 워커가 담당.
- 이렇게 하면 가맹점 서버가 느리거나 죽어도 Kafka 컨슈머가 막히지 않는다 (소비는 빠르게 끝나고, 전송은 비동기로).
- HTTP 전송은 외부 호출이라 트랜잭션 밖. 결과만 delivery 상태로 갱신.

### 6.2 단계별 실패 / 서버 다운 복구

| 죽는 시점 | 결과 | 복구 |
|---|---|---|
| 이벤트 소비 ~ delivery 생성 전 | 오프셋 미커밋 | 재consume (event_id 멱등) |
| delivery 생성 후 ~ 전송 전 | PENDING 상태로 남음 | 전송 워커가 PENDING 집어서 전송 |
| 전송 중 서버 다운 | 응답 못 받음 | next_attempt_at 도래 시 재시도 (가맹점이 멱등 처리하도록 event_id 포함 권장) |
| 3회 실패 | FAILED 기록 | 자동 복구 없음. 가맹점이 조회 API로 확인 / 운영자 수동 처리 |

> 가맹점 입장의 멱등: 같은 결제에 대해 재시도로 웹훅이 여러 번 갈 수 있으므로, 본문에 event_id(또는 paymentKey + eventType)를 포함해 가맹점이 중복 통지를 무시할 수 있게 한다.

---

## 7. 트랜잭션 경계 요약

| 작업 | 물리 트랜잭션 | 외부 호출 | 멱등 |
|---|---|---|---|
| 이벤트 소비 | 단일 (delivery 생성) | merchant-service 조회 | event_id UNIQUE |
| 웹훅 전송 | 상태 갱신만 | HTTP POST (트랜잭션 밖) | 본문 event_id로 가맹점 측 멱등 |

> 알림은 결제 흐름에서 분리된 곁가지다. at-least-once 소비 + event_id 멱등으로 "여러 번 와도 안전"하게 받고, 전송 실패는 제한적 재시도 후 기록으로 남긴다.

---

## 8. 강화 체크리스트 적용 결과

| 강화 항목 | 적용 |
|---|---|
| CloudEvents | 소비 측. CloudEvents id를 멱등키로 사용 |
| Redis 락 TTL | 직접 락 없음 (전송 워커 다중화 시 delivery 행 잠금 또는 상태 기반 처리로 충분) |
| 트랜잭션 경계 | 소비/전송 분리, HTTP는 트랜잭션 밖 (6장) |
| 단계별 실패 복구 | 죽는 시점별 복구 표 (6.2). 단 3회 후 자동 복구는 의도적으로 없음 |
| 컨슈머 재처리 | at-least-once + event_id 멱등. 단, 웹훅 전송 재시도는 3회로 제한 |
| 게이트웨이 분리 | 전송 이력 조회만 외부, 나머지는 내부 동작 |

---

## 9. 범위에 대한 메모

알림은 의도적으로 완결성을 낮춘 영역이다.
- 웹훅 재시도 3회 후 자동 재발송 없음 → 가맹점은 결제 조회 API로 결과 확인 가능하므로 알림 유실이 치명적이지 않음.
- 이는 "모든 걸 완벽히 만들기"보다 **중요도에 따라 완성도를 배분하는 판단**이다. 결제·정산은 정확성에 투자하고, 알림은 합리적 수준에서 멈춘다.
