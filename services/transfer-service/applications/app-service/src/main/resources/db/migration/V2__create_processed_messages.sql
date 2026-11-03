CREATE TABLE IF NOT EXISTS processed_messages (
    transfer_id VARCHAR(36) NOT NULL,
    consumer VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    result_type VARCHAR(255),
    result_payload TEXT,
    PRIMARY KEY (transfer_id, consumer)
);
