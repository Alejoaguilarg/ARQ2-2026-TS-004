-- Notification Service schema (spec §2: RecipientContact and NotificationLog)

-- Copy of the contact data owned by Identity, provisioned with the initial data set.
-- Notification never calls Identity at runtime.
CREATE TABLE recipient_contacts (
    user_id           UUID         PRIMARY KEY,
    email             VARCHAR(254) NOT NULL,
    phone             VARCHAR(32),
    push_token        VARCHAR(512),
    preferred_channel VARCHAR(8)   NOT NULL,
    CONSTRAINT ck_recipient_contacts_channel CHECK (preferred_channel IN ('PUSH', 'EMAIL', 'SMS'))
);

-- One row per notification. A retry updates the same row (status and attempts);
-- a SENT row is never sent again.
CREATE TABLE notification_log (
    notification_id UUID        PRIMARY KEY,
    transfer_id     UUID        NOT NULL,
    event_type      VARCHAR(32) NOT NULL,
    user_id         UUID        NOT NULL,
    channel         VARCHAR(8)  NOT NULL,
    status          VARCHAR(8)  NOT NULL,
    attempts        INTEGER     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_log_transfer_event_user UNIQUE (transfer_id, event_type, user_id),
    CONSTRAINT ck_notification_log_event_type
        CHECK (event_type IN ('TransferCompleted', 'TransferRejected', 'TransferReversed')),
    CONSTRAINT ck_notification_log_channel CHECK (channel IN ('PUSH', 'EMAIL', 'SMS')),
    CONSTRAINT ck_notification_log_status CHECK (status IN ('SENT', 'FAILED')),
    CONSTRAINT ck_notification_log_attempts CHECK (attempts >= 1)
);

CREATE INDEX ix_notification_log_user_id ON notification_log (user_id);
