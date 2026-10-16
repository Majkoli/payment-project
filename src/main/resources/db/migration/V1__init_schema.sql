CREATE TABLE charges (
    id             UUID PRIMARY KEY,
    account_id     VARCHAR(255) NOT NULL,
    amount_minor   BIGINT       NOT NULL,           -- amount in öre, e.g. 49900 = 499 SEK
    currency       VARCHAR(3)   NOT NULL DEFAULT 'SEK',
    status         VARCHAR(32)  NOT NULL,
    psp_reference  VARCHAR(128) UNIQUE,
    failure_reason TEXT,
    version        INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_charges_status_created_at ON charges (status, created_at);

CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_path    VARCHAR(255),
    request_hash    CHAR(64),
    response_status INT,
    response_body   TEXT,
    status          VARCHAR(32),
    charge_id       UUID REFERENCES charges (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ
);

CREATE TABLE webhook_events (
    event_id     VARCHAR(128) PRIMARY KEY,
    event_type   VARCHAR(64),
    payload      TEXT,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    signature    VARCHAR(256)
);
