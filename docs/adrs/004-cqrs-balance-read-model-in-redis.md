# ADR 004: CQRS Balance Read Model in Redis Updated by Versioned Events

- **Status:** Accepted
- **Date:** 2026-10-16
- **Feature:** 2 (balance read optimized for extreme speed and high concurrency)

## Context and Problem

Balance is the most read endpoint (every app open, every refresh, every pre-transfer screen). Serving it from the transactional database competes with debits and credits for connections and row locks, exactly when concurrency is highest. Reads can tolerate milliseconds of staleness; transfer validation cannot.

## Considered Alternatives

1. **Read from PostgreSQL with read replicas:** simpler, but replica lag is unbounded and each read still costs a query and a connection.
2. **Cache-aside with TTL (read DB on miss, expire after N seconds):** easy, but values can be stale for the whole TTL and invalidation races can resurrect old balances.
3. **CQRS read model in Redis, updated from `BalanceChanged` events carrying a monotonically increasing `version`, with cache-aside only as fallback on a miss.**

## Decision

- Wallet emits `BalanceChanged {walletId, userId, available, version}` through its outbox on every debit, credit and reversal.
- A projection consumer in the Wallet Service writes `balance:{userId}` in Redis with a Lua compare-and-set: only if `incoming.version > stored.version`.
- `GET /wallets/me/balance` reads Redis; on a miss it reads PostgreSQL and warms the cache.
- Debits never use the cache: they use the conditional atomic update on PostgreSQL (`balance >= amount`).

## Trade-offs

**Positives (what we gain)**
- Sub-millisecond reads that do not touch the transactional database.
- Version check makes the read model safe against out-of-order and duplicate events.
- Write path and read path scale independently.

**Negatives (risks / what we lose)**
- Eventual consistency of a few milliseconds after a transfer: acceptable for display; the response includes `version` and `asOf`.
- One more consumer to operate: mitigated by DLQ and a rebuild job that recomputes the cache from PostgreSQL.
- Redis memory: one small hash per user, negligible.

## Final Justification

It meets "extreme speed and high concurrency" without weakening correctness: the authoritative value stays in PostgreSQL for money movement, while reads are served from a model that can only move forward in version.
