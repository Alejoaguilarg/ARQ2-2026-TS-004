# Development Plan — Finova (ARQ2-2026-TS-004)

Two-wave plan aligned with the project rules. Every milestone has a concrete artifact in this repo and all team members are expected to produce verifiable commits in both waves.

---

## Wave 1 — Planning, Design, Specification (SDD)

Goal: a Tech Spec (TS-004 in Google Docs) and the design artifacts in `/docs` that **explicitly solve the Finova scenario** and cover the 5 mandatory features.

### Milestone 1.1 — Domain analysis (DDD)

- **Core domain:** Money movement between wallets (P2P transfer).
- **Supporting subdomains:** Identity & sessions, Notifications, Audit/Reporting, Rate limiting / anti-abuse.
- **Bounded contexts (initial):**
  - `Identity` — users, credentials, financial sessions.
  - `Wallet` — accounts, balances, holds.
  - `Transfer` — Saga orchestration, idempotency, compensation.
  - `Ledger` — append-only journal of every attempt (source of truth for audit).
  - `Notification` — async consumer.
- **Deliverable:** section 2 of TS-004 + glossary in `/docs/spec.md`.

### Milestone 1.2 — C4 model (Context + Containers)

- Draw **Level 1 (System Context)** and **Level 2 (Containers)**:
  - Clients (Web, Mobile) → **API Gateway** → services.
  - Gateway owns: auth-check, idempotency store (Redis), rate limiting.
  - `Transfer Orchestrator` publishes/consumes on RabbitMQ.
  - `Wallet-Service` writes to Postgres + Outbox table; a dispatcher relays to RabbitMQ.
  - `Ledger-Service` consumes every state transition and writes append-only.
  - `Redis` serves hot balance reads, invalidated by Outbox events.
- **Deliverable:** `/docs/assets/c4-context.png`, `/docs/assets/c4-containers.png`, and section 3 of TS-004.

### Milestone 1.3 — ADRs (minimum 3; we will produce 5)

Each ADR is a markdown file in `/docs/adrs/`.

1. **ADR-001 — Idempotency at the API Gateway.** Trade-off: Gateway vs. per-service idempotency. Chosen: Gateway with Redis + DB unique constraint on `(user_id, idempotency_key)`.
2. **ADR-002 — Saga over 2PC.** Why orchestration-based Saga beats two-phase commit for cross-service money moves.
3. **ADR-003 — Outbox pattern for Wallet writes.** Guarantees atomicity between DB write and event publication.
4. **ADR-004 — Redis for balance reads.** Latency/consistency trade-off; invalidation driven by Outbox events, bounded staleness.
5. **ADR-005 — Append-only Ledger.** Immutable journal (no UPDATE/DELETE), audit guarantees, retention.

### Milestone 1.4 — Contract specification (`/docs/spec.md`)

OpenAPI 3.1 for synchronous APIs and AsyncAPI 2.6 for events.

- **Auth:** `POST /auth/login`, `POST /auth/step-up`, token model.
- **Wallet:** `GET /wallets/me/balance` (cacheable), `GET /wallets/me/transactions` (paginated).
- **Transfers:** `POST /transfers` — requires `Idempotency-Key` header; returns `202 Accepted` with `transferId`; `GET /transfers/{id}` returns Saga state.
- **Events (RabbitMQ):**
  - `transfer.requested`, `wallet.debited`, `wallet.credited`,
    `transfer.failed`, `transfer.compensated`, `notification.requested`.
- **Error model:** typed problem+json; `409` for idempotency replay with different payload; `422` for insufficient funds.
- **Data schemas:** `User`, `Wallet`, `Transfer`, `LedgerEntry`.

### Milestone 1.5 — Tech Spec assembly

- Create Google Doc **TS-004**, share with `brandoncadavid1@gmail.com` (Editor).
- Paste the official template, fill sections 1–5.
- Insert the GitHub repo link in section 1.
- Each member commits at least one meaningful change in `/docs` during this wave.

**Exit criteria for Wave 1:** TS-004 complete, `/docs/spec.md` lints clean (`redocly lint`, `spectral`), `/docs/adrs` has ≥3 approved ADRs, C4 images exported, every team member has commits.

---

## Wave 2 — Implementation (monorepo)

Monorepo under this same repo. Documentation stays intact under `/docs`.

### Target folder structure

```
/gateway/                 # API Gateway (Node/Express or Kong+plugin)
/services/
  auth/
  wallet/
  transfer-orchestrator/
  ledger/
  notification/
/libs/                    # shared schemas, generated clients
/infra/
  rabbitmq/
  postgres/
  redis/
docker-compose.yml
```

### Milestone 2.1 — Baseline scaffolding (week 1)

- Pick stack (proposal: TypeScript + NestJS for services; Postgres per service; Redis; RabbitMQ).
- `docker-compose.yml` boots: gateway, 5 services, Postgres (one DB per service), Redis, RabbitMQ with management UI.
- CI: lint + typecheck + unit tests on every push.

### Milestone 2.2 — Feature 1: Auth + financial sessions

- Password + argon2, refresh tokens, short-lived access tokens.
- **Financial session** claim with `step_up=true` required for transfers over configurable threshold.
- Gateway validates JWT, injects `userId` + `sessionId` to downstream.

### Milestone 2.3 — Feature 2: Fast balance read

- `wallet-service` exposes `GET /wallets/me/balance`.
- Write path updates Postgres + Outbox; a dispatcher publishes `wallet.balance_changed`.
- Cache layer (Redis) is the primary read path; miss → DB → warm cache.
- Load test target: p99 < 20 ms at 2 k RPS on local compose (documented in `/docs/perf.md`).

### Milestone 2.4 — Feature 3: Idempotency at Gateway

- Middleware reading `Idempotency-Key` header on `POST /transfers`.
- Redis stores `{key → (request hash, response, status)}` with TTL (e.g., 24 h).
- Replay with same key + same payload → cached response.
- Replay with same key + different payload → `409 Conflict`.
- Secondary safety net: unique index on `(user_id, idempotency_key)` in `transfer` table.

### Milestone 2.5 — Feature 4: Saga + Outbox with compensation

- `transfer-orchestrator` state machine: `REQUESTED → DEBITED → CREDITED → COMPLETED`, with compensations `DEBITED → COMPENSATING → REVERSED`.
- Wallet writes always go through Outbox (same DB transaction).
- Outbox dispatcher publishes to RabbitMQ with at-least-once semantics.
- Idempotent consumers: deduplicate by `(transferId, step)`.
- Timeout + retry policies; dead-letter queue for manual review.
- Chaos test: kill wallet-service between debit and credit → expect automatic reversal within SLA.

### Milestone 2.6 — Feature 5: Append-only Ledger

- `ledger-service` consumes every transfer event and inserts rows into an **append-only** table:
  - `REVOKE UPDATE, DELETE` on the table for the service role.
  - Row contains: `event_id`, `transfer_id`, `type`, `amount`, `actor`, `prev_hash`, `hash`, `occurred_at`.
  - Hash-chain each row so tampering is detectable.
- `GET /ledger/transfers/{id}` returns the full timeline for audit.

### Milestone 2.7 — Hardening & demo

- End-to-end tests simulating the two original failure modes:
  - **Network micro-cut** between debit and credit → Saga compensation.
  - **User mashing Transfer button** → single successful transfer via idempotency key.
- Observability: structured logs, correlation IDs, Prometheus metrics for Saga states and idempotency hits.
- README-updated runbook: `docker compose up`, demo script, Postman collection.

---

## Working agreements

- **Branching:** feature branches, PRs reviewed by at least one teammate.
- **Commits:** every member commits in **both** waves; use conventional commits.
- **Definition of done:** code + tests + updated OpenAPI/AsyncAPI + ADR if a decision changed.
- **Weekly checkpoints:** Monday planning, Thursday sync, Friday demo.

## Risk register (initial)

| Risk | Mitigation |
|------|------------|
| Saga complexity grows | Keep orchestrator thin; one state machine; integration tests per transition |
| Outbox dispatcher lag | Monitor lag metric; scale dispatcher horizontally |
| Idempotency store eviction before client retry | TTL ≥ 24h; persist to DB as fallback |
| Ledger hash-chain gaps | Reconciliation job comparing event stream vs. ledger |
| Uneven team contribution | Weekly audit of `git shortlog -sne` |
