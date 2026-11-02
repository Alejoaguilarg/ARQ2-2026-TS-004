-- ============================================================
-- Audit Service - Initial schema
-- ============================================================

-- Sequence used to establish the total order of the audit chain
CREATE SEQUENCE audit_seq
    START WITH 1
    INCREMENT BY 1;

-- ============================================================
-- AUDIT RECORDS
-- Append-only, hash-chained audit trail
-- ============================================================

CREATE TABLE audit_records (
                               seq              BIGINT NOT NULL DEFAULT nextval('audit_seq'),
                               event_id         UUID NOT NULL,

                               source           VARCHAR(20) NOT NULL,
                               stage            VARCHAR(20) NOT NULL,
                               outcome          VARCHAR(20) NOT NULL,
                               reason           VARCHAR(100),

                               transfer_id      UUID,
                               user_id          UUID,
                               idempotency_key  VARCHAR(255),

                               amount           NUMERIC(19,2),
                               http_status      INTEGER,

                               correlation_id   UUID NOT NULL,

                               occurred_at      TIMESTAMPTZ NOT NULL,
                               recorded_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                               prev_hash        VARCHAR(64) NOT NULL,
                               hash             VARCHAR(64) NOT NULL,

                               PRIMARY KEY (seq, recorded_at),

                               CONSTRAINT chk_audit_source
                                   CHECK (source IN ('GATEWAY', 'TRANSFER', 'WALLET')),

                               CONSTRAINT chk_audit_stage
                                   CHECK (stage IN (
                                                    'REQUEST',
                                                    'DEBIT',
                                                    'CREDIT',
                                                    'REVERSAL',
                                                    'TRANSFER'
                                       )),

                               CONSTRAINT chk_audit_outcome
                                   CHECK (outcome IN (
                                                      'ACCEPTED',
                                                      'REJECTED',
                                                      'DUPLICATE',
                                                      'FAILED',
                                                      'COMPENSATED',
                                                      'COMPLETED',
                                                      'APPLIED'
                                       )),

                               CONSTRAINT chk_audit_amount
                                   CHECK (amount IS NULL OR amount > 0),

                               CONSTRAINT chk_prev_hash
                                   CHECK (prev_hash ~ '^[a-f0-9]{64}$'),

    CONSTRAINT chk_hash
        CHECK (hash ~ '^[a-f0-9]{64}$')
)
    PARTITION BY RANGE (recorded_at);


-- ============================================================
-- EVENT IDEMPOTENCY
-- ============================================================

CREATE TABLE audit_event_ids (
                                 event_id UUID PRIMARY KEY,
                                 seq      BIGINT NOT NULL
);


-- ============================================================
-- Useful indexes
-- ============================================================

CREATE INDEX idx_audit_records_transfer_id
    ON audit_records (transfer_id);

CREATE INDEX idx_audit_records_user_id
    ON audit_records (user_id);

CREATE INDEX idx_audit_records_correlation_id
    ON audit_records (correlation_id);

CREATE INDEX idx_audit_records_occurred_at
    ON audit_records (occurred_at);

CREATE INDEX idx_audit_records_seq
    ON audit_records (seq);
