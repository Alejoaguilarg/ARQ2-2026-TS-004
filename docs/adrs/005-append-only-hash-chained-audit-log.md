# ADR 005: Append-only, Hash-chained Audit Log Fed Asynchronously

- **Status:** Accepted
- **Date:** 2026-10-16
- **Feature:** 5 (immutable record for auditing every financial transaction attempt)

## Context and Problem

Finova must prove what happened to every transfer attempt, including rejected and duplicate ones, and the record must be impossible to modify silently. Auditing must not add latency to the transfer path or block it when the audit store is down.

## Considered Alternatives

1. **Audit columns in each service's own tables:** no single trail, rows can be updated, and rejected attempts at the Gateway would be lost.
2. **Event store / Kafka log as the audit record:** naturally append-only, but retention, querying by user or date, and compliance reporting are harder, and it adds Kafka to the stack.
3. **Dedicated Audit Service with an append-only PostgreSQL table, insert-only privileges, triggers that reject UPDATE/DELETE, and SHA-256 hash chaining, fed from `audit.events`.**
4. **External immutable storage (WORM object storage, ledger database):** strongest immutability, but outside the open-source, Docker Compose scope of the course.

## Decision

- Gateway, Transfer and Wallet publish `AttemptRecorded` for every attempt and outcome (accepted, rejected, duplicate, failed, compensated) to the `audit.events` exchange.
- The Audit Service stores them in `audit_records` (PostgreSQL): the service role has only `INSERT` and `SELECT`; a trigger rejects `UPDATE`, `DELETE` and `TRUNCATE`.
- Each row stores `prevHash` and `hash = SHA-256(prevHash || canonicalJson(row))`; `GET /audit/verify` recomputes the chain. Duplicate events are ignored by `eventId`.
- Monthly partitioning and WAL archiving for retention.

## Trade-offs

**Positives (what we gain)**
- One complete, queryable trail of every attempt, including those stopped at the Gateway.
- Tampering is prevented by privileges and detected by the hash chain.
- Zero latency on the transfer path; if Audit is down, events wait in the durable queue.

**Negatives (risks / what we lose)**
- Asynchronous: a record appears milliseconds after the attempt. Mitigated because the queue is durable and producers use the outbox (Transfer, Wallet).
- The Gateway has no outbox; mitigated with local retry and because the Transfer Service also audits every accepted request.
- A DBA with superuser rights could still rewrite rows; the hash chain makes it detectable, and periodic anchoring of the last hash outside the database is planned.

## Final Justification

It satisfies "append-only for every attempt" with standard open-source tools, keeps auditing off the critical path, and gives compliance both prevention (no update path) and detection (hash chain).
