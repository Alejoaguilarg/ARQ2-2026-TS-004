# Tasks

## 1. Infrastructure Setup

- [ ] 1.1 Provision PostgreSQL (4 databases: transfer, wallet, identity, audit) and verify connections
- [ ] 1.2 Provision RabbitMQ cluster with HA quorum queues and verify publisher confirms
- [ ] 1.3 Provision Redis cluster (sentinel or cluster mode) and verify SETNX + pub/sub
- [ ] 1.4 Create docker-compose.yml for local dev with all infrastructure and verify `docker-compose up` works
- [ ] 1.5 Configure OpenTelemetry collector and verify traces propagate across services

## 2. Identity Service (Financial Session & Auth)

- [ ] 2.1 Scaffold Spring Boot project with hexagonal structure and verify builds
- [ ] 2.2 Implement User aggregate, credentials, device registry; verify unit tests
- [ ] 2.3 Implement JWT access token (15 min) + rotating refresh token (7 days) issuance; verify token refresh rotation test
- [ ] 2.4 Implement refresh token reuse detection → full revocation; verify audit event on reuse
- [ ] 2.5 Implement device fingerprint binding on token issuance; verify rejection from unknown device
- [ ] 2.6 Implement financial session endpoint (POST /financial-sessions) with PIN/OTP verification; verify 5-min single-use token issued
- [ ] 2.7 Implement Redis revocation set (JTI → timestamp) checked by Gateway; verify revoked token rejected in < 1ms
- [ ] 2.8 Implement rate limiting + lockout (5 failed in 15 min → 30 min lockout) at Gateway; verify 429 response
- [ ] 2.9 Write OpenAPI spec for Identity endpoints; verify spec generates valid client

## 3. Wallet Service (Balance, Ledger, CQRS)

- [ ] 3.1 Scaffold Spring Boot project with hexagonal structure and verify builds
- [ ] 3.2 Implement Wallet aggregate with balance invariant (non-negative) and optimistic versioning; verify concurrent debit test
- [ ] 3.3 Implement LedgerEntry append-only table (debit/credit/reversal) with transferId idempotency key; verify duplicate message ignored
- [ ] 3.4 Implement DebitWallet, CreditWallet, ReverseDebit command handlers (idempotent per transferId); verify each handler test
- [ ] 3.5 Implement BalanceChanged event publishing with monotonically increasing version; verify version increments
- [ ] 3.6 Implement Redis cache updater consuming BalanceChanged; verify stale version (v3 vs v5) discarded
- [ ] 3.7 Implement GET /balance reading from Redis with 5-min TTL fallback to DB; verify < 5ms p99 latency under load
- [ ] 3.8 Write OpenAPI spec for Wallet endpoints; verify spec generates valid client

## 4. Transfer Service (Saga Orchestration + Outbox)

- [ ] 4.1 Scaffold Spring Boot project with hexagonal structure and verify builds
- [ ] 4.2 Implement Transfer aggregate state machine (PENDING → DEBITED → CREDITED → COMPLETED | REVERSED | REJECTED); verify state transitions
- [ ] 4.3 Implement Transactional Outbox: transfer_created + DebitWallet in single transaction; verify outbox row on commit
- [ ] 4.4 Implement Outbox Relay publishing to RabbitMQ with publisher confirms; verify message published after commit
- [ ] 4.5 Implement saga orchestration: on WalletDebited → publish CreditWallet; on WalletCredited → mark COMPLETED; verify successful flow test
- [ ] 4.6 Implement compensation: on CreditWallet failure after max retries → publish ReverseDebit → mark REVERSED; verify reversal test
- [ ] 4.7 Implement saga timeout (30s default) triggering compensation; verify timeout test
- [ ] 4.8 Implement unique constraint on (idempotency_key, user_id) in Transfer DB; verify 409 on duplicate
- [ ] 4.9 Implement GET /transfers/{id}/status returning saga state + timestamps; verify polling test
- [ ] 4.10 Write OpenAPI spec for Transfer endpoints; verify spec generates valid client

## 5. API Gateway (Idempotency + Routing + Auth)

- [ ] 5.1 Scaffold Spring Cloud Gateway project and verify builds
- [ ] 5.2 Implement Idempotency-Key filter: require header on POST /transfers; verify 400 without key
- [ ] 5.3 Implement Redis SETNX registration with request SHA-256 hash + 24h TTL; verify first request proceeds
- [ ] 5.4 Implement duplicate key handling: identical body → 409 with status / replay response; different body → 409 IDEMPOTENCY_KEY_CONFLICT
- [ ] 5.5 Implement key scoping to (user_id, endpoint) from validated JWT; verify cross-user isolation
- [ ] 5.6 Implement response caching for completed transfers (status, headers, body); verify replay returns stored 202
- [ ] 5.7 Integrate JWT validation + Redis revocation check + financial-session header validation; verify 401/403 on invalid
- [ ] 5.8 Configure rate limiting on auth endpoints; verify 429 after threshold
- [ ] 5.9 Write OpenAPI spec for Gateway routes; verify spec generates valid client

## 6. Audit Service (Immutable Log + Hash Chain)

- [ ] 6.1 Scaffold Spring Boot project with hexagonal structure and verify builds
- [ ] 6.2 Implement AuditEvent consumer for all domains (Gateway, Transfer, Wallet); verify event consumption test
- [ ] 6.3 Implement append-only Audit table with INSERT-only DB user; verify DELETE/UPDATE blocked by DB
- [ ] 6.4 Implement SHA-256 hash chaining: hash = SHA256(payload || prev_hash); verify chain integrity test
- [ ] 6.5 Implement async processing (no blocking transfer path); verify transfer completes before audit persisted
- [ ] 6.6 Implement query endpoints: GET /audit?transferId=, GET /audit?userId=&from=&to=; verify compliance query test
- [ ] 6.7 Configure WAL archiving + monthly partitioning; verify point-in-time recovery test
- [ ] 6.8 Write OpenAPI spec for Audit endpoints; verify spec generates valid client

## 7. Notification Service (Async Consumer)

- [ ] 7.1 Scaffold Spring Boot project and verify builds
- [ ] 7.2 Implement consumers for TransferCompleted, TransferReversed, TransferRejected events; verify event handling
- [ ] 7.3 Implement best-effort push/email/SMS delivery with dead-letter queue; verify DLQ on failure

## 8. Integration & E2E Tests

- [ ] 8.1 E2E: Successful transfer A→B verifies balances, saga state, audit trail, notification
- [ ] 8.2 E2E: Insufficient funds → REJECTED, no balance change, audit logged
- [ ] 8.3 E2E: Network failure mid-saga → REVERSED, sender balance restored, audit shows compensation
- [ ] 8.4 E2E: Duplicate Idempotency-Key → single debit, response replayed, audit shows duplicate attempt
- [ ] 8.5 E2E: Concurrent transfers from same wallet → optimistic locking prevents overdraft
- [ ] 8.6 E2E: Balance read reflects transfer within 100ms (eventual consistency)
- [ ] 8.7 E2E: Financial session required for transfer; expired/reused session rejected
- [ ] 8.8 E2E: Tampered audit row detected by hash chain verification
- [ ] 8.9 Load test: 1000 concurrent transfers, verify no lost updates, p99 latency targets met
- [ ] 8.10 Verify OpenAPI specs combined into single spec.md at /docs/spec.md per Wave 1 deliverable

## Workflow follow-up

- Archive the change after all tasks verified and Wave 1 deliverables (Tech Spec, spec.md, ADRs, C4 diagrams) are complete.
- Verify archived specs appear in `openspec/specs/` with correct capability paths.