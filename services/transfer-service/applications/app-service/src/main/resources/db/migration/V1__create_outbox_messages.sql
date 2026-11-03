CREATE TABLE IF NOT EXISTS outbox_messages (
    id VARCHAR(36) PRIMARY KEY,
    exchange VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP
);
