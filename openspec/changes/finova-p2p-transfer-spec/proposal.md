# Proposal

## Why

Finova's current microservices migration uses chained synchronous HTTP calls (User-Service → Wallet-Service → Notification-Service) which causes two critical failures: (1) money debited from sender but never credited to receiver when network fails mid-chain, and (2) duplicate charges when users retry transfers due to missing idempotency. This spec defines a robust P2P transfer system that guarantees exactly-once financial semantics, automatic compensation on failure, extreme-speed balance reads, and immutable audit trails.

## What Changes

- **New Capability: P2P Transfer Orchestration** — Saga-based transfer with automatic compensation (reversal) on failure
- **New Capability: Idempotent Transfer Gateway** — API Gateway enforces idempotency keys to prevent duplicate charges
- **New Capability: Wallet Balance CQRS** — Separate read model (Redis cache) for extreme-speed balance queries
- **New Capability: Financial Session & Auth** — Step-up authorization (PIN/OTP) for transfers, short-lived tokens with revocation
- **New Capability: Immutable Financial Audit** — Append-only audit log with hash chaining for every transfer attempt

All capabilities are new (greenfield project). No existing capabilities to modify.

## Capabilities

### New Capabilities

- `transfer/orchestration`: Saga orchestration for P2P transfers — debit sender, credit receiver, complete or compensate with automatic reversal. Handles partial failures, retries, and idempotent wallet operations.
- `transfer/idempotency`: Idempotency enforcement at API Gateway — `Idempotency-Key` header registration with request hash, TTL, conflict detection, and duplicate response replay. Defense-in-depth with unique constraint in Transfer DB.
- `wallet/balance-read`: CQRS read model for wallet balances — Redis cache updated via versioned balance-changed events from Wallet service. Stale version discard, eventual consistency (milliseconds) for reads, authoritative DB for writes.
- `identity/financial-session`: Financial session management — step-up authorization (PIN/OTP), short-lived transfer-bound tokens, device binding, refresh-token rotation, revocation via Redis, rate limiting and lockout at Gateway.
- `audit/financial-log`: Immutable append-only audit log — every transfer attempt (accepted, rejected, duplicate, failed, compensated) published as event and stored in insert-only table with SHA-256 hash chaining for tamper evidence.

### Modified Capabilities

- None (greenfield project)

## Impact

- **APIs**: New `POST /transfers` with idempotency key, `GET /balance` from cache, `GET /transfers/{id}/status`
- **Events**: `DebitWallet`, `CreditWallet`, `WalletDebited`, `WalletCredited`, `ReverseDebit`, `TransferCompleted`, `TransferReversed`, `BalanceChanged`, `AuditEvent`
- **Infrastructure**: RabbitMQ broker, Redis (idempotency + balance cache + session/revocation), PostgreSQL per service (Transfer DB, Wallet DB, Audit DB, Identity DB)
- **Services**: API Gateway, Transfer Service, Wallet Service, Identity Service, Audit Service, Notification Service
- **Data**: Each service owns its database (database per service pattern), no shared databases