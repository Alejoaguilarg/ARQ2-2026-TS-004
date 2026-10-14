# Design

## Context

See proposal.md for motivation. This is a greenfield project implementing Case 4: Finova P2P Virtual Wallet. The current broken architecture uses chained synchronous HTTP (User-Service → Wallet-Service → Notification-Service) causing lost money on network failures and duplicate charges on retries. We replace with async saga orchestration, gateway idempotency, CQRS balance reads, financial sessions, and immutable audit.

## Goals / Non-Goals

**Goals:**
- Exactly-once P2P transfer semantics under partial failures and retries
- Sub-millisecond balance reads at high concurrency (CQRS + Redis)
- Zero-latency audit trail with tamper evidence (append-only + hash chaining)
- Robust auth with step-up authorization for financial operations
- Horizontal scalability: stateless services, Redis for cross-cutting concerns, RabbitMQ for async

**Non-Goals:**
- Multi-currency or cross-border transfers (out of scope for Case 4)
- Real-time notification delivery guarantees (best-effort via Notification Service)
- User onboarding/KYC flows (Identity Service assumes users exist)
- Frontend/mobile app implementation (API contracts only)

## Decisions

### 1. Orchestrated Saga over Choreography
**Decision:** Transfer Service orchestrates the saga (debit → credit → complete/compensate) via explicit commands to Wallet Service through RabbitMQ.
**Rationale:** Centralized orchestration gives visibility into saga state, simplifies compensation logic, and avoids distributed callback chains. Choreography would scatter saga logic across services, making debugging and compensation harder.
**Alternatives considered:** Choreographed saga (events only), Two-phase commit (2PC - not available across microservices, blocks resources).

### 2. Transactional Outbox Pattern for Reliable Messaging
**Decision:** Each service writes state changes and outbox messages in a single DB transaction. A separate relay process publishes outbox messages to RabbitMQ.
**Rationale:** Guarantees at-least-once delivery without dual-write problem. If service crashes after DB commit but before publish, relay picks up on restart.
**Alternatives considered:** Direct publish from transaction (risks loss on crash), CDC (Debezium - adds operational complexity).

### 3. Idempotency at Gateway + Defense in Depth
**Decision:** API Gateway enforces Idempotency-Key with Redis SETNX + request hash. Transfer Service enforces unique constraint on (idempotency_key, user_id).
**Rationale:** Gateway stops 99% of duplicates at edge (fast, no service hit). DB constraint catches any bypass. Request hash prevents key reuse with different payload.
**Alternatives considered:** Idempotency only in Transfer Service (slower, duplicate work), Database-only deduplication (no fast replay).

### 4. CQRS for Balance Reads
**Decision:** Separate read model (Redis cache) updated from versioned BalanceChanged events. Writes go to PostgreSQL with optimistic locking.
**Rationale:** Balance reads are high-volume, low-latency. Cache serves reads; DB enforces invariants. Versioning prevents stale overwrites from out-of-order events.
**Alternatives considered:** Read from DB directly (latency under load), Materialized view in same DB (couples read/write scaling).

### 5. RabbitMQ over Kafka
**Decision:** RabbitMQ for command/event messaging between services.
**Rationale:** Lower operational overhead for this scale. Supports per-message TTL, dead-letter queues, and flexible routing for saga commands. Kafka overkill for < 10k msg/sec.
**Alternatives considered:** Kafka (higher throughput, but more ops complexity), gRPC direct (couples services, no durability).

### 6. PostgreSQL per Service (Database per Service)
**Decision:** Each service owns its schema: Transfer DB, Wallet DB, Identity DB, Audit DB. No shared databases.
**Rationale:** Loose coupling, independent deployments, clear ownership. Polyglot persistence not needed; PostgreSQL handles all domains well.
**Alternatives considered:** Shared database (couples deployments, schema conflicts), Polyglot (Redis for everything - not ACID for ledger).

### 7. Append-Only Audit with Hash Chaining
**Decision:** Audit table insert-only, application user has no UPDATE/DELETE. Each row: SHA256(payload || prev_hash).
**Rationale:** Tamper evidence required for financial audit. Hash chain allows integrity verification from any point to genesis.
**Alternatives considered:** Event store only (no SQL query flexibility), Blockchain (overkill, performance), WORM storage (vendor lock-in).

### 8. Financial Session as Short-Lived Scoped Token
**Decision:** Separate financial-session token (5 min, single-use, bound to amount/device) required for transfers, distinct from access token.
**Rationale:** Limits blast radius of token theft. Step-up (PIN/OTP) proves user presence at transfer time. Single-use prevents replay.
**Alternatives considered:** Elevated scope on access token (longer exposure), Per-transfer signed request (client complexity).

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Saga timeout too short → false compensation | Configurable timeout (default 30s) + max retries (3) with exponential backoff; observability alerts on REVERSED |
| Redis cache stale → user sees wrong balance | Versioned events + TTL (5 min) safety net; reads eventually consistent by ms; write path always uses DB |
| Idempotency key collision across users | Key scoped to (user_id, endpoint) in Redis; unique constraint in Transfer DB includes user_id |
| RabbitMQ broker single point of failure | HA queue mirroring (quorum queues); publisher confirms; dead-letter for poison messages |
| Audit log growth unbounded | Partition by month; WAL archiving; compliance retention policy (7 years) enforced by cron |
| Financial session UX friction (PIN every transfer) | Configurable: allow "trusted device" skip for small amounts (< threshold); risk-based auth |
| Distributed tracing across async boundaries | Correlation ID (transferId) propagated in all messages and logs; OpenTelemetry integration |

## Migration Plan

Not applicable (greenfield). Deployment order for Wave 2:
1. Infrastructure: PostgreSQL, RabbitMQ, Redis
2. Identity Service (auth foundation)
3. Wallet Service (balance, ledger, cache updater)
4. Transfer Service (saga, outbox)
5. API Gateway (routing, idempotency, auth validation)
6. Audit Service (async consumer)
7. Notification Service (async consumer)

Rollback: Blue-green per service; database migrations backward-compatible.

## Open Questions

- Exact Redis cluster topology (sentinel vs cluster mode) — decide in Wave 2 infra sprint
- Whether to use Spring Cloud Stream or raw RabbitMQ client — team preference
- Audit hash algorithm: SHA-256 (chosen) vs Blake3 (faster) — benchmark in Wave 2