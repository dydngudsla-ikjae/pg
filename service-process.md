# 결제 플랫폼 — 프로세스 & 메시지 설계

> 서비스 간 동기/비동기 흐름과 Kafka 메시지 설계
> 관련 문서: [시스템 구조 & 정책](./service-spec.md) · [API 명세](./api-spec.md)
>
> 표기: 동기 호출 `──>`, 비동기(Kafka) 발행 `~~>`

---

## 1. 메시지(Kafka) 설계

### 토픽 목록

| 토픽 | 프로듀서 | 컨슈머 | 용도 |
|---|---|---|---|
| `payment.paid` | payment-service | settlement-service, notification-service | 결제 완료 (일반·정기·가상계좌 공통) |
| `payment.cancelled` | payment-service | settlement-service, notification-service | 결제 취소 |
| `va.deposited` | virtual-account-service | payment-service | 가상계좌 입금 완료 |
| `va.expired` | virtual-account-service | payment-service | 가상계좌 입금 기한 만료 |

> **알림 경로 단일화**: notification-service는 `payment.paid` / `payment.cancelled`만 소비한다. `va.deposited`는 payment-service만 소비하며, payment-service가 결제를 PAID로 전이한 뒤 `payment.paid`를 발행하면 그때 notification-service가 알림을 보낸다. (notification이 `va.deposited`까지 소비하면 가상계좌 입금 1건에 알림이 2번 가므로 금지)

### 메시지 공통 규약

- **공통 envelope**: 모든 이벤트는 아래 메타 필드를 포함한다.
```json
{
  "eventId": "evt_uuid",
  "eventType": "payment.paid",
  "occurredAt": "2026-05-31T14:23:01+09:00",
  "payload": { }
}
```
- **멱등성**: 컨슈머는 `eventId`를 저장하여 중복 수신을 무시한다.
- **파티션 키**: `merchantId` 또는 `paymentKey`를 키로 사용해 순서를 보장한다.
- **스키마 재사용**: 일반결제·정기결제·가상계좌 입금은 모두 `payment.paid`로 합류하여 정산·알림 컨슈머가 출처를 몰라도 처리 가능하다.

### 이벤트 payload

**payment.paid**
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

**payment.cancelled**
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
> `cancelKey`/`merchantCancelId`를 포함해 정산·알림·추적에서 어떤 취소 건인지 식별 가능.

**va.deposited**
```json
{
  "paymentKey": "pay_va_8c",
  "merchantId": 1001,
  "amount": 30000,
  "depositedAt": "2026-05-31T16:10:00+09:00"
}
```

**va.expired**
```json
{
  "paymentKey": "pay_va_8c",
  "merchantId": 1001,
  "expiredAt": "2026-06-01T14:30:00+09:00"
}
```
> payment-service가 소비하여 결제를 `READY → FAILED`(failureCode=`VA_EXPIRED`)로 전이.

---

## 2. 프로세스 흐름

### 2.1 카드 결제 승인 (동기 + 비동기 혼합)

```
가맹점
  │ ① POST /v1/payments (동기, Idempotency-Key)
  ▼
[api-gateway] ──> API Key 검증, 가맹점 ACTIVE 확인
  ▼
[payment-service]
  │ ② 멱등성 키 체크 (Redis + DB)
  │ ③ 결제 READY 저장, 한도 검증
  │ ④ Mock PG 승인 호출 (동기, timeout 3s) ──> [mock-pg]
  │ ⑤ 승인 응답 → PAID 전이, 카드 메타정보(cardCompany, cardLast4, maskedCardNumber, pgTid)만 저장
  │ ⑥ 멱등성 응답 캐싱
  │ ⑦ payment.paid 발행 ~~> Kafka
  │ ⑧ 가맹점에 200 응답 (동기 종료)
  ▼
Kafka payment.paid
  ├~~> [settlement-service]   정산 대상 적재
  └~~> [notification-service] 가맹점 웹훅 전송
```

**핵심**
- 승인 결과는 가맹점이 즉시 받아야 하므로 ①~⑧ 동기, 정산·알림은 비동기 분리
- PG 호출 타임아웃 시 **재시도하지 않음** → 중복 승인 방지
- **타임아웃 복구 경로**: 타임아웃 시 결제를 `UNKNOWN`으로 두고, payment-service 내부 스케줄러가 `POST /internal/payments/{paymentKey}/verify`를 호출 → `GET /mock-pg/payments/by-payment-key/{paymentKey}`로 실제 승인 여부 확인 → `PAID` 또는 `FAILED`로 확정. PAID 확정 시 그 시점에 `payment.paid` 발행. 가맹점은 `GET /v1/payments/{paymentKey}` 조회로만 결과 확인 (상태 확정 명령은 외부에 노출 안 함)
- 명확한 거절(mock-pg `DECLINE`)은 `UNKNOWN`을 거치지 않고 `READY → FAILED`로 직접 전이
- 관련 API: `POST /v1/payments`, `POST /mock-pg/approve`, `POST /internal/payments/{paymentKey}/verify`, `GET /mock-pg/payments/by-payment-key/{paymentKey}`

### 2.2 가상계좌 발급 → 입금 (비동기)

```
[1단계: 발급 - 동기]
가맹점 ──> ① POST /v1/payments (method=VIRTUAL_ACCOUNT)
  ▼
[payment-service]            ← 결제의 단일 소유자
  │ ② 결제 레코드 생성 (Payment status=READY)
  │ ③ 계좌 발급 요청 ──> [virtual-account-service] POST /internal/virtual-accounts
  ▼
[virtual-account-service]
  │ ④ 가상계좌 채번 (VA status=WAITING_DEPOSIT), 기한 24h
  │ ⑤ 계좌 정보 응답 ──> payment-service
  ▼
[payment-service]
  │ ⑥ 계좌번호 포함 응답 (동기 종료) ──> 가맹점
       (Payment=READY, VirtualAccount=WAITING_DEPOSIT)

[2단계: 입금 - 비동기, 나중에 발생]
Mock 은행 ──> ⑦ POST /v1/virtual-accounts/deposit-callback
  ▼
[virtual-account-service]
  │ ⑧ 금액 일치 검증, 중복 콜백 차단(bankTxId 멱등)
  │ ⑨ VA status=DEPOSITED 전이
  │ ⑩ va.deposited 발행 ~~> Kafka
  ▼
Kafka va.deposited
  └~~> [payment-service]      Payment READY→PAID 전이 + payment.paid 발행
                                  ▼
                            Kafka payment.paid
                              ├~~> [settlement-service]   정산 적재
                              └~~> [notification-service] 가맹점 웹훅 전송 (알림은 여기서 1회만)

[3단계: 만료 - 스케줄러]
[virtual-account-service @Scheduled]
  │ 미입금 + 기한 초과 → VA status=EXPIRED
  │ va.expired 발행 ~~> Kafka
  ▼
Kafka va.expired
  └~~> [payment-service]  Payment READY→FAILED 전이 (failureCode=VA_EXPIRED)
```

**핵심**
- **결제 단일 소유자(A안)**: payment-service만 결제 레코드를 생성·전이. virtual-account-service는 계좌 발급/입금 콜백만 책임 → DB per Service 원칙 유지
- 발급과 입금이 시간적으로 분리 → 이벤트 드리븐의 대표 사례
- 입금 콜백 `bankTxId` 멱등 처리로 은행 중복 전송 방어
- Payment와 VirtualAccount는 별도 상태값. 한 쌍으로 움직임 (READY+WAITING_DEPOSIT → PAID+DEPOSITED)
- 입금/만료 모두 이벤트로 일관 처리: 입금은 `va.deposited`, 만료는 `va.expired` → payment-service가 결제 상태를 전이
- 관련 API: `POST /v1/payments`(VIRTUAL_ACCOUNT), `POST /internal/virtual-accounts`, `POST /v1/virtual-accounts/deposit-callback`, `POST /mock-bank/deposit`

### 2.3 결제 취소 (동기 + 보상 이벤트)

```
가맹점 ──> ① POST /v1/payments/{paymentKey}/cancel (동기, merchantCancelId 포함)
  ▼
[payment-service]
  │ ② merchantCancelId 중복 검증 (unique(merchant_id, merchant_cancel_id))
  │ ③ 상태 검증 (PAID / PARTIAL_CANCELLED만 허용)
  │ ④ 취소 가능 금액 검증 (누적 취소 ≤ 원금)
  │ ⑤ Mock PG 취소 호출 (동기) ──> [mock-pg]
  │ ⑥ Cancel 레코드 생성, 상태 전이
  │     (잔액 0 → CANCELLED, 잔액 > 0 → PARTIAL_CANCELLED)
  │ ⑦ payment.cancelled 발행 ~~> Kafka
  │ ⑧ 취소 응답 (동기 종료)
  ▼
Kafka payment.cancelled
  ├~~> [settlement-service]   정산 차감/상계 처리
  └~~> [notification-service] 가맹점 웹훅 전송
```

**핵심**
- `merchantCancelId`로 가맹점의 동일 취소 중복 요청을 차단하고 추적성 확보
- 정산 전 취소는 차감, 정산 후 취소는 다음 정산일 negative item(ADJUSTMENT)으로 반영
- 관련 API: `POST /v1/payments/{paymentKey}/cancel`, `POST /mock-pg/cancel`

### 2.4 정기결제 자동청구 (스케줄러 기반)

```
[등록 - 동기]
가맹점 ──> ① POST /v1/billing/keys (카드 등록)
  ▼ 빌링키 발급 (카드번호 미저장, 토큰만)
가맹점 ──> ② POST /v1/billing/subscriptions (구독 생성)
  ▼ 구독 저장 (다음 청구일 지정)

[청구 - 스케줄러, 매일]
[billing-service @Scheduled]
  │ ③ 당일 청구 대상 구독 조회
  │ ④ 각 구독 → POST /v1/payments (method=BILLING, billingKey) 호출, 건별 트랜잭션
  │     인증: 가맹점 API Key 대신 내부 헤더 (X-Internal-Service: billing-service, X-Merchant-Id)
  │ ⑤ 성공 → 다음 청구일 갱신
  │     실패 → 재시도 큐 (최대 3일)
  │ ⑥ 3회 실패 → 구독 SUSPENDED
  ▼
payment-service가 결제 처리 → payment.paid 발행 (정산·알림 흐름 합류)
```

**핵심**
- 카드번호 미저장 → 빌링키(토큰)만 보관, 유출 시에도 재사용 불가
- 결제 호출은 `POST /v1/payments`의 `method: BILLING` 경로 사용 → 결제 레코드는 payment-service가 소유 (별도 내부 결제 API 두지 않음)
- billing-service는 가맹점 API Key를 보관하지 않고 내부 인증 헤더로 호출
- 건별 트랜잭션으로 한 건 실패가 전체 배치를 멈추지 않게 격리
- 일반 결제와 동일한 `payment.paid` 이벤트로 합류
- 관련 API: `POST /v1/billing/keys`, `POST /v1/billing/subscriptions`, `POST /v1/billing/charge`, `POST /v1/payments`(BILLING)

### 2.5 정산 배치 (Spring Batch)

```
[settlement-service @Scheduled - 매일]
  │ ① 정산 기준일 결정 (전일)
  │ ② 집계 대상 수집:
  │     - 기준일 승인된 payment.paid       → settlement_items (type=PAYMENT, +)
  │     - 기준일 이전·미반영 payment.cancelled → settlement_items (type=CANCEL, -)
  │     - 정산 완료 후 취소된 건            → 다음 정산일 (type=ADJUSTMENT, -)
  │ ③ 가맹점별 집계 → 수수료 2.5% 계산 → payout 산출
  │ ④ settlements(헤더) + settlement_items(상세) 저장
  ▼
정산 완료 (status=COMPLETED)
```

**집계 대상 (단순 PAID가 아님)**
| 항목 | type | 부호 |
|---|---|---|
| 기준일 승인 결제 | PAYMENT | + |
| 기준일 이전 발생·미반영 취소 | CANCEL | − |
| 정산 후 취소 (소급) | ADJUSTMENT | − (다음 정산일) |

**필요 테이블**
- `settlements`: 가맹점·정산일 단위 헤더 (totalAmount, feeAmount, payoutAmount, status)
- `settlement_items`: 건별 상세 (paymentKey, type, amount, feeAmount, payoutAmount)

**핵심**
- 이벤트 수신 시점이 아니라 **정산 기준일** 기준으로 집계 → 정산 후 취소는 소급하지 않고 다음 정산일에 음수 항목으로 반영
- 관련 API: `GET /v1/settlements`, `GET /v1/settlements/{id}`, `POST /v1/settlements/run`

---

## 3. 동기 / 비동기 구분 요약

| 처리 | 방식 | 이유 |
|---|---|---|
| 결제 승인 결과 | 동기 | 가맹점이 즉시 결과를 받아야 함 |
| 취소 결과 | 동기 | 동일 |
| 가상계좌 발급 | 동기 | 계좌번호를 즉시 응답 |
| 가상계좌 입금 처리 | 비동기 | 입금은 미래 시점에 발생 |
| 정산 적재/집계 | 비동기 | 결제 성공과 분리, 장애 격리 |
| 가맹점 웹훅 알림 | 비동기 | 실패해도 결제에 영향 없어야 함 |
| 정기결제 청구 | 비동기(스케줄러) | 정해진 시각에 일괄 실행 |
