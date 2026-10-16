# ADR 003: Mandatory Idempotency-Key at the API Gateway backed by Redis

- **Status:** Accepted
- **Date:** 2026-10-16
- **Features:** 3 (strict idempotency at the Gateway), 5 (duplicates are audited)

## Context and Problem

Under latency, users tap "Transfer" several times per second and clients retry on timeouts. Without validation at the entry point each request debits again, causing overdraft. The case explicitly requires idempotency **at the Gateway**. The check must be atomic under concurrency (10 identical requests at the same millisecond), fast (it is on every transfer), and must distinguish "still running", "already done" and "same key, different request".

## Considered Alternatives

1. **Idempotency only inside the Transfer Service (DB unique constraint):** correct, but duplicates still reach the service and its database, and it does not satisfy "at the Gateway".
2. **Gateway check backed by PostgreSQL:** durable, but adds a database round trip and lock contention to every transfer at the edge.
3. **Gateway check backed by Redis (`SET NX` + TTL) plus a DB unique constraint as defense in depth.**
4. **Client-side debouncing only:** helpful for UX but not a guarantee (retries, multiple devices, malicious clients).

## Decision

- `Idempotency-Key` (UUID) is **mandatory** on `POST /transfers`; missing key → `400`.
- A Spring Cloud Gateway filter runs `SET idem:{userId}:{key} {IN_PROGRESS, requestHash} NX EX 86400`:
  - key created → route the request; on response store `{COMPLETED, requestHash, status, body}`;
  - exists and `IN_PROGRESS` → `409 IDEMPOTENCY_IN_PROGRESS`;
  - exists, `COMPLETED`, same hash → replay the stored response (`Idempotent-Replayed: true`);
  - exists, different hash → `422 IDEMPOTENCY_KEY_REUSED`.
- Every rejected or replayed request publishes `AttemptRecorded` to `audit.events`.
- Defense in depth: `UNIQUE(sender_user_id, idempotency_key)` in `transfer_db`, and Wallet operations are idempotent per `transferId`.
- The client generates the key once per transfer intent and disables the button while the request is in flight.

## Trade-offs

**Positives (what we gain)**
- Duplicates are stopped before reaching any domain service; one debit per intent.
- Sub-millisecond atomic check; scales with Redis.
- Clear, standard semantics for clients (409 / replay / 422).

**Negatives (risks / what we lose)**
- Redis becomes critical for transfers: mitigated with Redis persistence (AOF) and replica; if Redis is unavailable the Gateway fails closed for `POST /transfers` (503) while balance reads fall back to the DB.
- Key expiry after 24 h: a later retry is still blocked by the DB unique constraint.
- Gateway crash after routing but before storing `COMPLETED`: the key stays `IN_PROGRESS` until TTL; the client polls `GET /transfers/{id}`, and the DB constraint still prevents a second transfer.

## Final Justification

This puts the protection exactly where the case demands it, with an atomic primitive designed for this race, and keeps a second independent barrier in the core domain so the guarantee does not depend on a single component.
