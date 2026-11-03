# Audit Service

Append-only, SHA-256 hash-chained record of every transfer attempt and outcome (ADR 005, spec §2 `AuditRecord`, §3.10, §3.11, §4.8, Feature 5).
Java 21, Spring Boot 3.4, Bancolombia clean-architecture scaffold, PostgreSQL 16 (`audit_db`), RabbitMQ.

## What it does

| | |
|---|---|
| Consumes | `AttemptRecorded` from `audit.attempts.q` (exchange `audit.events`, key `audit.attempt`), published by the Gateway, Transfer and Wallet |
| Stores | One row per attempt in `audit_records` with the next `seq`, `prevHash` and `hash` (F5.2); duplicates by `eventId` are ignored (F5.3) |
| Serves | `GET /audit/records` and `GET /audit/verify`, role `AUDITOR` only (F5.6, F5.7, F5.5) |
| Never | Updates or deletes a record, calls another service, or sits on the transfer path (F5.8) |

## Modules

```
domain/model                     AttemptRecorded, AuditRecord, ChainLink, HashChain, AuditQuery, ports
domain/usecase                   RecordAttemptUseCase, QueryAuditRecordsUseCase, VerifyAuditChainUseCase
infrastructure/entry-points
  rabbitmq-consumer              AttemptRecordedListener (manual acks, retry, DLQ), AttemptRecordedReader
  api-rest                       AuditController, ApiExceptionHandler (ErrorResponse, spec §3.2)
infrastructure/driven-adapters
  jpa-repository                 JdbcAuditRecordRepository, Flyway V1, monthly partition maintenance
applications/app-service         Spring Boot application and configuration
```

The Redis and RabbitMQ-publisher adapters of the scaffold were removed: the Audit Service owns only `audit_db` and publishes nothing.

## Hash chain

`hash = SHA-256(prevHash || canonicalForm)`, lowercase hex; the first record links to 64 zeros.
`canonicalForm` is the record as compact JSON **without** `prevHash` and `hash`, keys sorted, timestamps as `yyyy-MM-ddTHH:mm:ss.ffffffZ`. It is the same string Python produces with:

```python
json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
```

so an auditor can recompute any hash outside the service (see `HashChainTest`, whose expected values were computed that way).
Timestamps are kept with microsecond precision and amounts are normalized (`"0150.00"` → `"150.00"`) before hashing, so a record read back from PostgreSQL hashes exactly as it did when it was written.

Appends are serialized: the queue has a single active consumer, the listener runs one thread, and each append takes a transaction-level advisory lock before reading the last link.

`GET /audit/verify` walks the range in batches of 1 000 and reports the first broken link: content that no longer matches its hash, a `prevHash` that does not match the previous record, or a missing `seq` (a deleted row).

## Database guarantees (Flyway `V1__create_audit_schema.sql`, `V2__service_login_role.sql`)

- The service logs in as its own role (`SPRING_DATASOURCE_USERNAME`, default `audit_app`), created by V2: not a superuser, not the owner, and its only privileges are inherited from `audit_rw`, which has **only** `INSERT` and `SELECT` on `audit_records` and `audit_event_ids`. There is no `SET ROLE` to undo: `RESET ROLE` gives nothing back.
- A trigger rejects `UPDATE`, `DELETE` and `TRUNCATE` for every role, owner included, on the parent table, every partition and `audit_event_ids` (F5.4).
- `audit_event_ids (event_id PRIMARY KEY, seq UNIQUE)` enforces one record per `eventId`; PostgreSQL does not allow that unique constraint on the partitioned table itself.
- `audit_records` is partitioned by month on `recorded_at` (UTC). The migration creates the current and next month; `AuditPartitionMaintenance` keeps them ahead every 6 h through `audit_ensure_month_partition()` (`SECURITY DEFINER`). `audit_records_default` catches a record whose month has no partition yet.
- Flyway runs as the database owner (`SPRING_FLYWAY_USER`, its own connection) and fills the service role's name and password from the datasource settings.
- A superuser can still switch triggers off; the hash chain is what detects that (ADR 005).

## Messaging (spec §4.2, §4.5)

| Situation | What happens |
|---|---|
| Stored or duplicate | `ack` |
| Body is not a valid `AttemptRecorded` | copy to `audit.attempts.q.dlq` (with header `x-audit-dead-letter-reason`), then `ack` |
| Technical error (DB down, timeout) | `nack` without requeue → `audit.attempts.q.retry` (5 s) → back to the queue |
| Technical error after 5 retries (`x-death` count) | copy to the DLQ, then `ack` |
| DLQ copy not confirmed by the broker | `nack`, so the message comes back through the retry queue |

The reader accepts the §4.1 envelope or the bare payload, takes `eventId` from `messageId` when the payload lacks it, and accepts `occurredAt` as RFC 3339 text or as Jackson writes an `Instant` with timestamps enabled.

Metrics: `audit_attempts_total{result="recorded|duplicate|retried|dead_lettered"}` on `/actuator/prometheus`.

To replay the DLQ once the cause is fixed, move its messages back to `audit.attempts.q` with a shovel (the management UI's *Move messages* needs `rabbitmq_shovel` and `rabbitmq_shovel_management`, which `infra/rabbitmq/enabled_plugins` does not enable today). Replayed attempts are appended at the end of the chain; duplicates are ignored.

## REST API

Both endpoints are routed by the Gateway, which checks the JWT and the role; the service checks `X-User-Roles` again.

```http
GET /audit/records?transferId={uuid}&userId={uuid}&from={date-time}&to={date-time}&page=0&size=50
GET /audit/verify?fromSeq=1&toSeq=500
```

- `/audit/records`: at least one of `transferId`, `userId`, `from`; `from` and `to` bound `occurredAt` (inclusive); `size` 1..200 (default 50); ordered by `seq`.
- Errors use `ErrorResponse`: `400 VALIDATION_ERROR` (with `details.field`), `403 FORBIDDEN_ROLE`, `500 INTERNAL_ERROR`.

## Run and test

```bash
./gradlew build                      # unit tests + Testcontainers tests (skipped when Docker is not running)
docker compose --profile apps up -d --build audit-service   # from the repository root
```

Local run without Docker Compose: PostgreSQL on `localhost:5432/audit_db` (user `audit`) and RabbitMQ on `localhost:5672` with `infra/rabbitmq/definitions.json` loaded. The service listens on port 8080, like every service behind the Gateway.
