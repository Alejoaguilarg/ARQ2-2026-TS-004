-- Audit Service schema (audit_db). Contract: docs/spec.md §2 (AuditRecord, AuditEventId), §4.5, §4.8,
-- §5 F5.1-F5.9 and ADR 005 (append-only, hash-chained audit log).
--
-- Guarantees built here:
--   * audit_records is append-only: the service works as role audit_rw, which has INSERT and SELECT
--     only, and a trigger rejects UPDATE, DELETE and TRUNCATE for everybody, owner included (F5.4).
--   * audit_event_ids enforces UNIQUE(eventId): PostgreSQL requires the partition key in every unique
--     constraint of a partitioned table, so it cannot live in audit_records (F5.3).
--   * audit_records is partitioned by month on recorded_at; audit_ensure_month_partition() creates the
--     partitions and the service calls it on a schedule (ADR 005, retention).
-- Flyway runs this script as the database owner; the service connects and then runs SET ROLE audit_rw.

-- ---------------------------------------------------------------- service role
DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_rw') THEN
        CREATE ROLE audit_rw NOLOGIN;
    END IF;
END;
$$;

-- The login role that runs the migrations (and the service) may switch to audit_rw.
GRANT audit_rw TO CURRENT_USER;
GRANT USAGE ON SCHEMA public TO audit_rw;

-- ---------------------------------------------------------------- append-only guard
CREATE FUNCTION audit_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- ---------------------------------------------------------------- audit_records (partitioned)
CREATE TABLE audit_records (
    seq             BIGINT        NOT NULL,
    event_id        UUID          NOT NULL,
    source          VARCHAR(16)   NOT NULL,
    stage           VARCHAR(16)   NOT NULL,
    outcome         VARCHAR(16)   NOT NULL,
    reason          TEXT,
    transfer_id     UUID,
    user_id         UUID,
    idempotency_key TEXT,
    amount          NUMERIC(19,2),
    http_status     INTEGER,
    correlation_id  UUID          NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL,
    recorded_at     TIMESTAMPTZ   NOT NULL,
    prev_hash       CHAR(64)      NOT NULL,
    hash            CHAR(64)      NOT NULL,
    CONSTRAINT pk_audit_records PRIMARY KEY (seq, recorded_at),
    CONSTRAINT ck_audit_seq_positive CHECK (seq >= 1),
    CONSTRAINT ck_audit_source CHECK (source IN ('GATEWAY', 'TRANSFER', 'WALLET')),
    CONSTRAINT ck_audit_stage CHECK (stage IN ('REQUEST', 'DEBIT', 'CREDIT', 'REVERSAL', 'TRANSFER')),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN
        ('ACCEPTED', 'REJECTED', 'DUPLICATE', 'FAILED', 'COMPENSATED', 'COMPLETED', 'APPLIED')),
    CONSTRAINT ck_audit_amount_non_negative CHECK (amount >= 0),
    CONSTRAINT ck_audit_http_status CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_audit_prev_hash_format CHECK (prev_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_audit_hash_format CHECK (hash ~ '^[a-f0-9]{64}$')
) PARTITION BY RANGE (recorded_at);

-- GET /audit/records filters (§3.10); every query is ordered by seq.
CREATE INDEX ix_audit_records_transfer ON audit_records (transfer_id, seq);
CREATE INDEX ix_audit_records_user ON audit_records (user_id, seq);
CREATE INDEX ix_audit_records_occurred ON audit_records (occurred_at, seq);

-- Row triggers on a partitioned table are cloned to every partition, present and future.
CREATE TRIGGER trg_audit_records_reject_update_delete
    BEFORE UPDATE OR DELETE ON audit_records
    FOR EACH ROW EXECUTE FUNCTION audit_reject_change();

-- Statement triggers are not cloned: each partition gets its own TRUNCATE guard (see below).
CREATE TRIGGER trg_audit_records_reject_truncate
    BEFORE TRUNCATE ON audit_records
    FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_change();

-- ---------------------------------------------------------------- audit_event_ids
CREATE TABLE audit_event_ids (
    event_id UUID   NOT NULL,
    seq      BIGINT NOT NULL,
    CONSTRAINT pk_audit_event_ids PRIMARY KEY (event_id),
    CONSTRAINT uq_audit_event_ids_seq UNIQUE (seq)
);

CREATE TRIGGER trg_audit_event_ids_reject_update_delete
    BEFORE UPDATE OR DELETE ON audit_event_ids
    FOR EACH ROW EXECUTE FUNCTION audit_reject_change();

CREATE TRIGGER trg_audit_event_ids_reject_truncate
    BEFORE TRUNCATE ON audit_event_ids
    FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_change();

-- ---------------------------------------------------------------- monthly partitions
-- Creates the partition of the month that contains p_month (UTC bounds) if it does not exist yet.
-- SECURITY DEFINER: audit_rw cannot create tables, but may call this function.
CREATE FUNCTION audit_ensure_month_partition(p_month DATE) RETURNS TEXT
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
    v_start DATE := date_trunc('month', p_month)::date;
    v_end   DATE := (date_trunc('month', p_month) + INTERVAL '1 month')::date;
    v_name  TEXT := format('audit_records_y%sm%s', to_char(v_start, 'YYYY'), to_char(v_start, 'MM'));
BEGIN
    IF to_regclass('public.' || v_name) IS NULL THEN
        EXECUTE format('CREATE TABLE public.%I PARTITION OF public.audit_records FOR VALUES FROM (%L) TO (%L)',
                       v_name, v_start || ' 00:00:00+00', v_end || ' 00:00:00+00');
        EXECUTE format('CREATE TRIGGER trg_reject_truncate BEFORE TRUNCATE ON public.%I '
                           || 'FOR EACH STATEMENT EXECUTE FUNCTION public.audit_reject_change()', v_name);
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC', v_name);
    END IF;
    RETURN v_name;
END;
$$;

-- Safety net: a record whose month has no partition yet lands here instead of failing.
CREATE TABLE audit_records_default PARTITION OF audit_records DEFAULT;
CREATE TRIGGER trg_reject_truncate
    BEFORE TRUNCATE ON audit_records_default
    FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_change();

SELECT audit_ensure_month_partition(CAST(now() AT TIME ZONE 'UTC' AS DATE));
SELECT audit_ensure_month_partition(CAST((now() AT TIME ZONE 'UTC') + INTERVAL '1 month' AS DATE));

-- ---------------------------------------------------------------- privileges (F5.4)
REVOKE ALL ON audit_records, audit_records_default, audit_event_ids FROM PUBLIC;
GRANT SELECT, INSERT ON audit_records, audit_event_ids TO audit_rw;

REVOKE ALL ON FUNCTION audit_ensure_month_partition(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_ensure_month_partition(DATE) TO audit_rw;
