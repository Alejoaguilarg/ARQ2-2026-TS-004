# Spec Delta

## Purpose

Provides extreme-speed, high-concurrency wallet balance reads through a CQRS read model backed by Redis cache, updated asynchronously from versioned balance-changed events. Reads are eventually consistent (milliseconds) while writes use the authoritative database.

## ADDED Requirements

### Requirement: Balance read served from Redis cache
The Wallet Service SHALL serve GET /balance requests from a Redis cache without querying the transactional database, achieving sub-millisecond latency under high concurrency.

#### Scenario: Balance read returns cached value
- **WHEN** client calls GET /balance with valid access token
- **THEN** Gateway validates token, Wallet Service reads from Redis, returns balance in < 5ms p99

#### Scenario: Cache miss falls back to database
- **WHEN** Redis cache has no entry for user's wallet
- **THEN** Wallet Service queries database, populates cache, returns balance

### Requirement: Cache updated via versioned balance-changed events
The Wallet Service SHALL publish BalanceChanged events with monotonically increasing version numbers after each successful debit, credit, or reversal. A cache updater SHALL apply events only when version is newer than cached version.

#### Scenario: Balance changed event updates cache
- **WHEN** Wallet Service completes debit/credit/reversal and publishes BalanceChanged v5
- **THEN** cache updater applies v5, cached balance reflects new value, version stored

#### Scenario: Stale event discarded
- **WHEN** cache updater receives BalanceChanged v3 but cached version is v5
- **THEN** event discarded, cache unchanged, no stale overwrite

### Requirement: Balance reads eventually consistent by milliseconds
The system SHALL guarantee that balance reads reflect the latest committed state within 100 milliseconds under normal operation.

#### Scenario: Read reflects recent transfer within milliseconds
- **WHEN** transfer completes and BalanceChanged published
- **THEN** subsequent GET /balance within 100ms returns updated balance

### Requirement: Authoritative balance validation uses database
Transfer saga debit/credit validation SHALL always read and write the authoritative Wallet database, never the cache, ensuring balance invariants (non-negative) are strictly enforced.

#### Scenario: Concurrent debits validated against database
- **WHEN** two concurrent DebitWallet commands for same wallet
- **THEN** both validated against database with optimistic locking, only one succeeds if insufficient balance

### Requirement: Cache TTL as safety net
The Redis cache SHALL have a TTL of 5 minutes as a safety net against updater failures, forcing periodic refresh from database.

#### Scenario: Expired cache entry refreshed from database
- **WHEN** cache entry TTL expires
- **THEN** next GET /balance queries database, repopulates cache with fresh version