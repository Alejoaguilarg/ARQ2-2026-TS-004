-- transfer_db (spec §2 Transfer, OutboxMessage, ProcessedMessage; ADR 002, ADR 006).
-- One local transaction writes the transfer, its processed_messages row and its outbox rows (§1.4, §4.6).

-- ---------------------------------------------------------------- transfers
CREATE TABLE transfers (
    transfer_id       UUID          NOT NULL,
    sender_user_id    UUID          NOT NULL,
    recipient_user_id UUID          NOT NULL,
    amount            NUMERIC(19,2) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    description       VARCHAR(140),
    idempotency_key   UUID          NOT NULL,
    request_hash      CHAR(64)      NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    failure_reason    VARCHAR(32),
    credit_attempts   SMALLINT      NOT NULL DEFAULT 0,
    correlation_id    UUID          NOT NULL,
    -- The outbox row of the last saga command, republished as it is by the sweeper (§4.6 rule 8).
    last_command_id   UUID          NOT NULL,
    last_command_at   TIMESTAMPTZ   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT pk_transfers PRIMARY KEY (transfer_id),
    -- §3.1.1 defense in depth: one transfer per sender and Idempotency-Key.
    CONSTRAINT uq_transfers_sender_idempotency_key UNIQUE (sender_user_id, idempotency_key),
    CONSTRAINT ck_transfers_amount CHECK (amount > 0),
    CONSTRAINT ck_transfers_currency CHECK (currency = 'COP'),
    CONSTRAINT ck_transfers_not_to_self CHECK (sender_user_id <> recipient_user_id),
    CONSTRAINT ck_transfers_request_hash CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_transfers_status CHECK (status IN
        ('PENDING', 'DEBITED', 'COMPLETED', 'REJECTED', 'COMPENSATING', 'REVERSED')),
    CONSTRAINT ck_transfers_failure_reason CHECK (failure_reason IN
        ('INSUFFICIENT_FUNDS', 'SENDER_WALLET_NOT_FOUND', 'RECIPIENT_WALLET_NOT_FOUND', 'CREDIT_TIMEOUT')),
    CONSTRAINT ck_transfers_credit_attempts CHECK (credit_attempts BETWEEN 0 AND 3)
);

-- The timeout sweeper scans only the transfers still running, by lastCommandAt (§4.6 rule 8).
CREATE INDEX ix_transfers_running ON transfers (last_command_at)
    WHERE status IN ('PENDING', 'DEBITED', 'COMPENSATING');

-- ---------------------------------------------------------------- processed_messages
CREATE TABLE processed_messages (
    message_id     UUID        NOT NULL,
    consumer       VARCHAR(64) NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL,
    -- Wallet only (spec §2 ProcessedMessage); always null in transfer_db.
    result_type    VARCHAR(32),
    result_payload JSONB,
    CONSTRAINT pk_processed_messages PRIMARY KEY (message_id, consumer)
);

-- ---------------------------------------------------------------- outbox_messages
CREATE TABLE outbox_messages (
    id           UUID         NOT NULL,
    exchange     VARCHAR(128) NOT NULL,
    routing_key  VARCHAR(128) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT pk_outbox_messages PRIMARY KEY (id)
);

-- Lets the relay find pending rows without scanning published ones.
CREATE INDEX ix_outbox_pending ON outbox_messages (created_at) WHERE published_at IS NULL;
