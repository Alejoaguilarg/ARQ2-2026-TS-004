# Spec Delta

## Purpose

Records every financial transaction attempt in an immutable append-only audit log with cryptographic hash chaining, providing tamper-evident forensic trail for compliance and dispute resolution. Auditing is asynchronous and adds zero latency to the transfer critical path.

## ADDED Requirements

### Requirement: Every transfer attempt produces audit event
The API Gateway, Transfer Service, and Wallet Service SHALL each publish AuditEvent for every transfer attempt: accepted, rejected, duplicate, failed, compensated, reversed.

#### Scenario: Gateway logs accepted transfer request
- **WHEN** Gateway validates and forwards POST /transfers
- **THEN** Gateway publishes AuditEvent {type: TRANSFER_ACCEPTED, transferId, userId, amount, timestamp}

#### Scenario: Gateway logs rejected request
- **WHEN** Gateway rejects request (missing idempotency key, invalid auth, rate limited)
- **THEN** Gateway publishes AuditEvent {type: TRANSFER_REJECTED, transferId, userId, reason, timestamp}

#### Scenario: Transfer Service logs saga state changes
- **WHEN** Transfer Service transitions transfer state (PENDING, DEBITED, CREDITED, COMPLETED, REVERSED, REJECTED)
- **THEN** Transfer Service publishes AuditEvent with new state and context

#### Scenario: Wallet Service logs balance operations
- **WHEN** Wallet Service executes debit, credit, or reversal
- **THEN** Wallet Service publishes AuditEvent {type: WALLET_DEBITED/CREDITED/REVERSED, walletId, transferId, amount, balanceBefore, balanceAfter}

### Requirement: Audit events stored in append-only table with hash chaining
The Audit Service SHALL store events in an insert-only PostgreSQL table with no UPDATE/DELETE privileges for application role. Each row SHALL include SHA-256 hash of (current_row || previous_row_hash) for tamper evidence.

#### Scenario: Audit record includes hash chain
- **WHEN** Audit Service persists AuditEvent
- **THEN** row inserted with hash = SHA256(payload || prev_hash), prev_hash from latest row

#### Scenario: Tamper detection via hash verification
- **WHEN** auditor verifies log integrity
- **THEN** recomputing hashes from genesis detects any modified/deleted/inserted rows

### Requirement: Async audit adds no latency to transfer path
The Audit Service SHALL consume events asynchronously from message broker; transfer saga SHALL NOT wait for audit persistence.

#### Scenario: Transfer completes before audit persisted
- **WHEN** transfer saga reaches COMPLETED
- **THEN** response returned to client immediately, audit persisted in background

### Requirement: Audit retention and immutability enforced at database level
The Audit database SHALL enforce: (1) INSERT-only permissions for application user, (2) no TRUNCATE/DROP, (3) row-level security preventing deletes, (4) WAL archiving for point-in-time recovery.

#### Scenario: Direct delete attempt blocked by database
- **WHEN** any SQL DELETE issued against audit table
- **THEN** database returns permission denied, row unchanged

### Requirement: Audit query by transferId, userId, date range
The Audit Service SHALL provide query endpoints for compliance: GET /audit?transferId=, GET /audit?userId=&from=&to=.

#### Scenario: Compliance team queries transfer audit trail
- **WHEN** GET /audit?transferId=txn-123
- **THEN** returns all audit events for that transfer in chronological order