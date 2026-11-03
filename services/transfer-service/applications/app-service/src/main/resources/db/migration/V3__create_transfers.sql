CREATE TABLE IF NOT EXISTS transfers (
    transfer_id VARCHAR(36) NOT NULL PRIMARY KEY,
    sender_user_id VARCHAR(36) NOT NULL,
    recipient_user_id VARCHAR(36) NOT NULL,
    amount VARCHAR(32) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL UNIQUE,
    request_hash VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(64),
    credit_attempts INTEGER NOT NULL DEFAULT 0,
    correlation_id VARCHAR(36),
    last_command_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_transfers_idempotency_key ON transfers(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_transfers_status ON transfers(status);
CREATE INDEX IF NOT EXISTS idx_transfers_last_command_at ON transfers(last_command_at);
