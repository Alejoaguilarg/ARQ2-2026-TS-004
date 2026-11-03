-- Outbox relay retries (audit finding H1, same as wallet-service V3): a row that fails to publish is retried later
-- with a growing delay, so it never blocks the rows after it and is never dropped. Before, the relay stopped at the
-- first failure and the next run took that same row first again: one row that always failed held every saga.
-- Existing rows get attempts = 0 and next_attempt_at = NULL (ready now), so they behave exactly as before.
-- ix_outbox_pending (created_at WHERE published_at IS NULL) is kept.
ALTER TABLE outbox_messages ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_messages ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE outbox_messages ADD COLUMN last_error VARCHAR(500);

ALTER TABLE outbox_messages ADD CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0);
