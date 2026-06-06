CREATE TYPE payment_status AS ENUM ('READY', 'PAID', 'PARTIAL_CANCELLED', 'CANCELLED', 'FAILED', 'UNKNOWN');
CREATE TYPE payment_method AS ENUM ('CARD', 'VIRTUAL_ACCOUNT', 'BILLING');
CREATE TYPE idempotency_status AS ENUM ('PROCESSING', 'COMPLETED');

CREATE TABLE payment (
    id                BIGSERIAL       PRIMARY KEY,
    merchant_id       BIGINT          NOT NULL,
    order_id          VARCHAR(64)     NOT NULL,
    payment_key       VARCHAR(64)     NOT NULL UNIQUE,
    status            VARCHAR(20)     NOT NULL,
    method            VARCHAR(20)     NOT NULL,
    amount            BIGINT          NOT NULL,
    cancelled_amount  BIGINT          NOT NULL DEFAULT 0,
    pg_tid            VARCHAR(128),
    card_company      VARCHAR(32),
    card_last4        VARCHAR(4),
    masked_card_number VARCHAR(20),
    installment_months INT            NOT NULL DEFAULT 0,
    failure_code      VARCHAR(32),
    failure_message   VARCHAR(256),
    paid_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payment_merchant_order UNIQUE (merchant_id, order_id)
);

CREATE INDEX idx_payment_merchant_id ON payment(merchant_id);
CREATE INDEX idx_payment_status ON payment(status);

CREATE TABLE payment_history (
    id           BIGSERIAL    PRIMARY KEY,
    payment_id   BIGINT       NOT NULL,
    from_status  VARCHAR(20),
    to_status    VARCHAR(20)  NOT NULL,
    reason       VARCHAR(256),
    changed_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_history_payment_id ON payment_history(payment_id);

CREATE TABLE cancel (
    id                 BIGSERIAL    PRIMARY KEY,
    payment_id         BIGINT       NOT NULL,
    merchant_id        BIGINT       NOT NULL,
    cancel_key         VARCHAR(64)  NOT NULL UNIQUE,
    merchant_cancel_id VARCHAR(64),
    cancel_amount      BIGINT       NOT NULL,
    remain_amount      BIGINT       NOT NULL,
    reason             VARCHAR(256),
    pg_cancel_tid      VARCHAR(128),
    cancelled_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cancel_merchant_cancel_id UNIQUE (merchant_id, merchant_cancel_id)
);

CREATE INDEX idx_cancel_payment_id ON cancel(payment_id);

CREATE TABLE idempotency_key (
    id              BIGSERIAL    PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    merchant_id     BIGINT       NOT NULL,
    http_method     VARCHAR(10)  NOT NULL,
    endpoint        VARCHAR(256) NOT NULL,
    payment_id      BIGINT,
    request_hash    VARCHAR(64),
    response_body   TEXT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING',
    expired_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_idempotency UNIQUE (merchant_id, http_method, endpoint, idempotency_key)
);

CREATE INDEX idx_idempotency_expired_at ON idempotency_key(expired_at);

CREATE TABLE outbox_event (
    id             BIGSERIAL    PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status ON outbox_event(status, created_at);