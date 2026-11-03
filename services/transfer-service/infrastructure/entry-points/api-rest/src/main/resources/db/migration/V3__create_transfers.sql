CREATE TABLE IF NOT EXISTS transfers (
    transfer_id VARCHAR(36) NOT NULL,
    sender_user_id VARCHAR(36) NOT NULL,
    recipient_user_id VARCHAR(36) NOT NULL,
    amount VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(32),
    credit_attempts SMALLINT NOT NULL DEFAULT 0,
    correlation_id VARCHAR(36) NOT NULL,
    last_command_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (transfer_id)
);

CREATE INDEX idx_transfers_status ON transfers (status);
CREATE INDEX idx_transfers_idempotency_key ON transfers (idempotency_key);
CREATE INDEX idx_transfers_last_command_at ON transfers (last_command_at);