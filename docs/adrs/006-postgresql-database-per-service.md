# ADR 006: PostgreSQL as the Relational Database, One per Service

- **Status:** Accepted
- **Date:** 2026-10-16
- **Features:** 2, 3, 4 and 5 (data integrity under concurrency)

## Context and Problem

Money movement needs strict ACID guarantees, conditional atomic updates, unique constraints and check constraints. Each bounded context must own its data so services deploy and fail independently, and the outbox must live in the same database as the state it describes.

## Considered Alternatives

1. **MongoDB:** flexible documents and multi-document transactions, but weaker relational constraints and the team has less experience modelling money with it.
2. **MySQL:** viable, but PostgreSQL offers `RETURNING`, JSONB for outbox payloads, partial indexes, declarative partitioning and richer trigger support.
3. **One shared PostgreSQL database for all services:** easier joins, but recreates coupling and breaks independent deployment.
4. **PostgreSQL 16, one database per service.**

## Decision

Each service owns a PostgreSQL 16 database (`identity_db`, `wallet_db`, `transfer_db`, `audit_db`, `notification_db`), deployed as separate containers in Docker Compose. No service reads another service's database; integration happens only through the Gateway (REST) and RabbitMQ. Schema migrations use Flyway per service.

## Trade-offs

**Positives (what we gain)**
- `UPDATE … WHERE balance >= :amount RETURNING` gives an atomic, overdraft-proof debit.
- `NUMERIC(19,2)`, `CHECK (balance >= 0)` and unique constraints enforce invariants in the database as a last line of defense.
- JSONB outbox, partitioning and triggers for the audit log come built in.
- Failure isolation and independent evolution per service.

**Negatives (risks / what we lose)**
- No cross-service joins: reports are built from events (Audit) instead.
- Five database containers to operate: mitigated with Docker Compose and shared configuration.

## Final Justification

Finova is a financial system where correctness beats schema flexibility. PostgreSQL gives the strongest open-source guarantees for the exact operations the case breaks today, and one database per service preserves the microservice boundaries that the saga relies on.
