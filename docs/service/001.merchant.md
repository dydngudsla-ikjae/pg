# 가맹점 서비스 (merchant-service)

> 가맹점 등록·관리, API 키 발급·검증을 담당하는 서비스.
> 다른 서비스의 인증 기반이 되는 출발점 서비스다.
> 관련 문서: [시스템 구조 & 정책](./service-spec.md) · [API 명세](./api-spec.md) · [프로세스 & 메시지 설계](./service-process.md)

---

## 1. 책임 범위

| 한다 | 안 한다 |
|---|---|
| 가맹점 등록/조회/상태 변경 | 결제 처리 (payment-service) |
| API 키 발급/폐기 | 정산 계좌로의 실제 송금 (settlement-service) |
| 웹훅 URL 관리 | 웹훅 실제 전송 (notification-service) |
| API 키 검증 정보 제공 (Gateway가 사용) | |

---

## 2. 정책

### 2.1 가맹점 상태

| 상태 | 의미 | 결제 가능 |
|---|---|---|
| ACTIVE | 정상 영업 | O |
| SUSPENDED | 정지 (정산계좌 문제, 위반 등) | X |

- 신규 등록 시 기본 `ACTIVE`.
- 상태 변경은 관리자 권한으로만 가능.

### 2.2 API 키 정책

| 항목 | 정책 |
|---|---|
| 형식 | `mk_{env}_{random}` (예: `mk_live_a1b2c3...`). env는 `live`/`test` |
| 생성 | `SecureRandom` 32바이트 이상 랜덤 |
| 저장 | **평문 저장 금지.** SHA-256 해시값만 DB에 저장 |
| 노출 | 발급 응답에서 **단 1회만** 원문 노출. 이후 재조회 불가 |
| 검증 | 요청 키를 SHA-256 해시하여 DB의 `key_hash`와 비교 |
| 식별 | 키 앞부분(`lookup_id`)으로 후보 조회 후 해시 대조 |
| 폐기 | `revoked_at` 설정으로 즉시 무효화 (soft delete) |

> SHA-256을 쓰는 이유: API 키는 매 요청마다 검증하므로 빠른 해시가 필요하다. 키 자체가 32바이트 랜덤이라 무차별 대입이 불가능해 빠른 해시로도 안전하다. (비밀번호처럼 사람이 만든 약한 값이 아니다)

### 2.3 가맹점 참조 전략 (다른 서비스 → merchant-service)

DB per Service 원칙상 payment-service 등은 가맹점 DB를 직접 조회할 수 없다. 따라서 다음과 같이 참조한다.

**현재 방식: 동기 호출**

- API 키 검증과 가맹점 상태 확인은 **Gateway가 merchant-service에 동기 호출**하여 처리한다.
- Gateway는 검증 결과로 `X-Merchant-Id`를 다운스트림에 전달한다.
- 즉 payment-service는 가맹점 상태를 직접 확인하지 않고, Gateway가 통과시킨 요청만 받는다.

```
가맹점 요청 (X-API-KEY)
  ▼
[Gateway] ──동기──> [merchant-service] : 키 검증 + ACTIVE 여부 확인
  ▼ (통과 시 X-Merchant-Id 부착)
[payment-service] : 결제 처리
```

**트레이드오프 & 개선 포인트**

- 단점: 매 요청마다 merchant-service 동기 호출 → 지연 발생, merchant-service 장애 시 전체 결제 영향.
- 개선(향후): Gateway에서 검증 결과를 **Redis에 캐싱(TTL 예: 5분)**. 가맹점 정지 시 캐시 무효화 또는 `merchant.suspended` 이벤트로 갱신. "정지 후 최대 TTL 만큼은 결제될 수 있음"을 허용하는 대신 성능·가용성 확보.

---

## 3. ERD

```
merchant (1) ──< (N) api_key
```

### merchant

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | 내부 식별자 (서비스 간 X-Merchant-Id로 사용) |
| merchant_no | VARCHAR(32) UNIQUE | 외부 노출용 관리 번호. `M`+yyyyMMdd+일련번호 (예: M20260531001) |
| name | VARCHAR(128) | 상호명 |
| business_no | VARCHAR(16) | 사업자등록번호 |
| representative_name | VARCHAR(64) | 대표자명 |
| status | merchant_status | ACTIVE / SUSPENDED |
| settlement_bank | VARCHAR(8) | 정산 은행 코드 |
| settlement_account | VARCHAR(32) | 정산 계좌번호 |
| webhook_url | VARCHAR(512) | 결제 결과 통지 URL |
| created_at / updated_at | TIMESTAMP | 생성/수정 시각 |

> **식별자 구분**: `merchantId`(PK)는 내부 서비스 간 식별자(X-Merchant-Id)로, `merchantNo`는 가맹점에게 노출 가능한 외부 관리 번호로 쓴다. 본 프로젝트는 단순함을 위해 외부 응답에도 `merchantId`를 노출하지만, 실무에서는 내부 PK 노출(순차 증가로 인한 규모 추측, 내부 구조 결합)을 피하기 위해 외부에는 `merchantNo`만 노출하는 방식이 권장된다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | 내부 식별자 |
| merchant_id | BIGINT FK | 소속 가맹점 |
| key_hash | VARCHAR(64) UNIQUE | SHA-256(키 원문). 검증 시 비교 대상 |
| key_prefix | VARCHAR(20) | `mk_live_` 등 환경 식별 프리픽스 |
| lookup_id | VARCHAR(16) | 키 앞부분. 빠른 후보 조회용 (인덱스) |
| description | VARCHAR(128) | 키 용도 메모 |
| last_used_at | TIMESTAMP | 마지막 사용 시각 |
| revoked_at | TIMESTAMP | 폐기 시각 (NULL이면 유효) |
| created_at | TIMESTAMP | 발급 시각 |

```sql
CREATE TYPE merchant_status AS ENUM ('ACTIVE', 'SUSPENDED');

CREATE TABLE merchant (
                          id                  BIGSERIAL       PRIMARY KEY,
                          merchant_no         VARCHAR(32)     NOT NULL UNIQUE,
                          name                VARCHAR(128)    NOT NULL,
                          business_no         VARCHAR(16)     NOT NULL,
                          representative_name VARCHAR(64)     NOT NULL,
                          status              merchant_status NOT NULL DEFAULT 'ACTIVE',
                          settlement_bank     VARCHAR(8),
                          settlement_account  VARCHAR(32),
                          webhook_url         VARCHAR(512),
                          created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
                          updated_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE api_key (
                         id           BIGSERIAL    PRIMARY KEY,
                         merchant_id  BIGINT       NOT NULL REFERENCES merchant(id),
                         key_hash     VARCHAR(64)  NOT NULL UNIQUE,
                         key_prefix   VARCHAR(20)  NOT NULL,
                         lookup_id    VARCHAR(16)  NOT NULL,
                         description  VARCHAR(128),
                         last_used_at TIMESTAMP,
                         revoked_at   TIMESTAMP,
                         created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_api_key_lookup_id ON api_key(lookup_id);
CREATE INDEX idx_api_key_merchant_id ON api_key(merchant_id);
```

---

## 4. API 스펙

> 공통: 응답은 `{ success, data, error }` 래퍼. 아래는 `data` 내부만 표기.
> 관리 API는 관리자/내부용으로, 가맹점 자신을 향한 공개 API와 클라이언트가 다름 (게이트웨이 분리 대상 — 5장 참고).

### POST /v1/merchants — 가맹점 등록
- 대상: 관리자(어드민)

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
  "merchantNo": "M20260531001",
  "name": "마이쇼핑몰",
  "status": "ACTIVE",
  "createdAt": "2026-05-31T09:00:00+09:00"
}
```

### GET /v1/merchants/{id} — 가맹점 조회
- 대상: 관리자

**Response (200)**
```json
{
  "merchantId": 1001,
  "merchantNo": "M20260531001",
  "name": "마이쇼핑몰",
  "businessNo": "123-45-67890",
  "representativeName": "홍길동",
  "status": "ACTIVE",
  "webhookUrl": "https://myshop.com/webhook",
  "createdAt": "2026-05-31T09:00:00+09:00"
}
```

### POST /v1/merchants/{id}/api-keys — API 키 발급
- 대상: 관리자

**Request**
```json
{
  "env": "live",
  "description": "운영 서버용 키"
}
```

**Response (201)**
```json
{
  "apiKey": "mk_live_a1b2c3d4e5f6g7h8i9j0",
  "keyPrefix": "mk_live_",
  "description": "운영 서버용 키",
  "issuedAt": "2026-05-31T09:05:00+09:00"
}
```
> `apiKey` 원문은 이 응답에서 **단 1회만** 제공된다. 서버는 해시만 저장하므로 이후 재조회 불가.

### DELETE /v1/merchants/{id}/api-keys/{keyId} — API 키 폐기
- 대상: 관리자

**Response (200)**
```json
{
  "keyId": 33,
  "revokedAt": "2026-05-31T10:00:00+09:00"
}
```

### PUT /v1/merchants/{id}/webhook — 웹훅 URL 등록/수정
- 대상: 관리자

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
- 대상: 관리자

**Request**
```json
{
  "status": "SUSPENDED"
}
```
> 상태 변경 사유(reason)는 MVP 범위에서 저장하지 않는다. 사유·이력 관리가 필요해지면 `merchant_status_history` 테이블을 두는 방향으로 확장한다.

**Response (200)**
```json
{
  "merchantId": 1001,
  "status": "SUSPENDED",
  "updatedAt": "2026-05-31T09:20:00+09:00"
}
```

### (내부) POST /internal/api-keys/verify — API 키 검증
- 대상: Gateway (내부 호출). `X-Internal-Service` 헤더 필요.
- 외부에 노출하지 않는다.

**Request**
```json
{
  "apiKey": "mk_live_a1b2c3d4e5f6g7h8i9j0"
}
```

**Response (200) — 유효 (정상 가맹점)**
```json
{
  "valid": true,
  "merchantId": 1001,
  "merchantStatus": "ACTIVE"
}
```

**Response (200) — 키는 유효하나 가맹점 정지**
```json
{
  "valid": true,
  "merchantId": 1001,
  "merchantStatus": "SUSPENDED"
}
```

**Response (200) — 키 자체가 무효**
```json
{
  "valid": false,
  "reason": "REVOKED"
}
```
> `reason`: `NOT_FOUND` | `REVOKED` (키 자체가 무효인 경우만)
>
> **`valid`와 `merchantStatus`는 분리된 관심사다.** `valid`는 "API 키가 진짜인가"(인증), `merchantStatus`는 "가맹점이 거래 가능한 상태인가"(정책)에 답한다. 키가 멀쩡한데 가맹점만 정지된 경우는 `valid=true` + `merchantStatus=SUSPENDED`로 응답한다. **차단 여부는 Gateway가 `merchantStatus`를 보고 판단**한다 (이 API는 사실만 제공). 이렇게 두면 향후 PENDING 같은 상태가 추가돼도 reason을 늘리지 않고 유연하게 대응할 수 있다.

---

## 5. 프로세스

### 5.1 API 키 발급

```
관리자 ──> POST /v1/merchants/{id}/api-keys
  ▼
[merchant-service]
  │ ① env에 맞는 프리픽스 결정 (mk_live_ / mk_test_)
  │ ② SecureRandom 32바이트 → 원문 키 생성
  │ ③ key_hash = SHA-256(원문)
  │ ④ lookup_id = 원문 앞 일부
  │ ⑤ api_key 저장 (해시만, 원문은 저장 안 함)
  │ ⑥ 응답에 원문 1회 노출 (동기 종료)
```
- 트랜잭션: ①~⑤가 단일 DB 트랜잭션. 외부 호출 없음.

### 5.2 API 키 검증 (Gateway → merchant-service, 동기)

```
가맹점 요청 (X-API-KEY)
  ▼
[Gateway] ──> POST /internal/api-keys/verify
  ▼
[merchant-service]
  │ ① 키에서 lookup_id 추출 → 후보 조회
  │ ② 요청 키 SHA-256 → key_hash 비교
  │ ③ revoked_at NULL 확인 → 키 유효성(valid) 판정
  │ ④ 소속 merchant status 조회 (막지 않고 그대로 응답에 실음)
  │ ⑤ last_used_at 갱신
  │ ⑥ { valid, merchantId, merchantStatus } 응답
  ▼
[Gateway] 판단:
  - valid=false              → 401 (인증 실패: 키 위조/폐기)
  - valid=true & ACTIVE      → X-Merchant-Id 부착 후 다운스트림 전달
  - valid=true & SUSPENDED   → 403 (인증은 됐으나 정지된 가맹점)
```
- 책임 분리: merchant-service는 "키가 유효한가 + 가맹점 상태가 무엇인가"라는 **사실만** 제공한다. 정지 가맹점을 실제로 **막는 판단은 Gateway**가 한다. (인증 vs 정책의 관심사 분리)
- 트랜잭션: 검증은 읽기 위주. `last_used_at` 갱신은 비핵심이라 별도 처리(실패해도 검증 결과에 영향 없음).

### 5.3 가맹점 상태 변경

```
관리자 ──> PATCH /v1/merchants/{id}/status
  ▼
[merchant-service] status 변경 + updated_at 갱신 (단일 트랜잭션)
```
- 현재는 이벤트 발행 없음. (향후 캐싱 도입 시 `merchant.suspended` 이벤트로 캐시 무효화 — 개선 포인트)

---

## 6. 트랜잭션 경계 요약

| 작업 | 트랜잭션 범위 | 외부 호출 |
|---|---|---|
| 가맹점 등록 | 단일 DB 트랜잭션 | 없음 |
| API 키 발급 | 단일 DB 트랜잭션 | 없음 |
| API 키 검증 | 읽기 (last_used_at는 분리) | 없음 |
| 상태 변경 | 단일 DB 트랜잭션 | 없음 |

> 가맹점 서비스는 분산 트랜잭션이나 이벤트 발행이 없어 트랜잭션이 단순하다. 복잡한 트랜잭션/복구 설계는 payment-service부터 본격화된다.

---

## 7. 강화 체크리스트 적용 결과

| 강화 항목 | 가맹점 서비스 적용 |
|---|---|
| CloudEvents/Kafka | 해당 약함 (이벤트 발행 없음). 향후 `merchant.suspended` 도입 시 적용 |
| Redis 락 TTL | 직접 락은 없음. 향후 키 검증 결과 캐싱(TTL)에서 등장 |
| 트랜잭션 경계 | 전부 단일 DB 트랜잭션 (6장) |
| 단계별 실패 복구 | 외부 호출이 없어 단순. 부분 실패 지점 없음 |
| 컨슈머 재처리 | 소비 이벤트 없음 |
| 게이트웨이 클라이언트 분리 | 관리자 API와 내부 검증 API를 클라이언트별로 구분 (5장) |