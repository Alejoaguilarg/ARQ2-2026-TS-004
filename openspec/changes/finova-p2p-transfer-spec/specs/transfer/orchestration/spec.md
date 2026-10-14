# Spec Delta

## Purpose

Orchestrates P2P money transfers through a saga that debits the sender, credits the receiver, and automatically executes compensating reversals when any step fails unrecoverably. Guarantees exactly-once financial semantics even under partial failures and retries.

## ADDED Requirements

### Requirement: Transfer saga executes debit-credit-complete sequence
The Transfer Service SHALL orchestrate a P2P transfer as a three-step saga: debit sender wallet, credit receiver wallet, mark transfer completed. Each step SHALL be a local ACID transaction in the Wallet Service.

#### Scenario: Successful transfer completes all steps
- **WHEN** a transfer is initiated with valid idempotency key and sufficient sender balance
- **THEN** sender balance is debited, receiver balance is credited, transfer status becomes COMPLETED

#### Scenario: Transfer rejected for insufficient funds
- **WHEN** a transfer is initiated but sender balance is less than transfer amount
- **THEN** transfer status becomes REJECTED, no wallet balances change, audit event recorded

### Requirement: Saga executes automatic compensation on credit failure
The Transfer Service SHALL automatically publish a ReverseDebit command when the credit step fails after bounded retries, restoring the sender's balance to its pre-transfer state.

#### Scenario: Credit fails after retries triggers compensation
- **WHEN** Wallet Service rejects CreditWallet after maximum retries (e.g., 3 retries with exponential backoff)
- **THEN** Transfer Service publishes ReverseDebit, sender balance is restored, transfer status becomes REVERSED

#### Scenario: Network failure during credit step triggers compensation
- **WHEN** message broker delivery of CreditWallet fails permanently
- **THEN** saga timeout triggers ReverseDebit, sender balance restored, transfer REVERSED

### Requirement: Wallet operations are idempotent per transferId
The Wallet Service SHALL treat each debit, credit, and reversal as idempotent operations keyed by transferId, ensuring redelivered messages never apply twice.

#### Scenario: Duplicate DebitWallet message ignored
- **WHEN** Wallet Service receives DebitWallet for a transferId already processed
- **THEN** Wallet Service returns WalletDebited without applying debit again

#### Scenario: Duplicate CreditWallet message ignored
- **WHEN** Wallet Service receives CreditWallet for a transferId already credited
- **THEN** Wallet Service returns WalletCredited without applying credit again

#### Scenario: Duplicate ReverseDebit message ignored
- **WHEN** Wallet Service receives ReverseDebit for a transferId already reversed
- **THEN** Wallet Service returns WalletReversed without applying reversal again

### Requirement: Transfer state persisted with outbox in single transaction
The Transfer Service SHALL persist transfer state and outbox message atomically in one database transaction, preventing message loss between state change and event publication.

#### Scenario: Transfer created with outbox message in same transaction
- **WHEN** Transfer Service receives transfer command
- **THEN** transfer record (PENDING) and DebitWallet outbox message written in single ACID transaction

#### Scenario: Outbox relay publishes message after transaction commits
- **WHEN** Transfer Service transaction commits successfully
- **THEN** outbox relay reads and publishes DebitWallet to message broker

### Requirement: Transfer status queryable by client
The Transfer Service SHALL expose a query endpoint returning current transfer status (PENDING, DEBITED, CREDITED, COMPLETED, REVERSED, REJECTED) for client polling.

#### Scenario: Client polls transfer status after initiation
- **WHEN** client calls GET /transfers/{id}/status after receiving 202 Accepted
- **THEN** response returns current saga state with timestamps