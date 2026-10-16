# ADR 002: Orchestrated Saga with Transactional Outbox over RabbitMQ for P2P Transfers

- **Status:** Accepted
- **Date:** 2026-10-16
- **Features:** 4 (Saga/Outbox with automatic compensation), 3 (defense in depth), 5 (audit events)

## Context and Problem

Today a transfer is two synchronous HTTP calls to Wallet (debit A, then credit B). If the network fails between them, A is debited and B is never credited, and nothing compensates. Debit and credit happen in different aggregates, and with database per service they cannot share one ACID transaction. We also need a guarantee that a state change and the message announcing it are never separated by a crash.

## Considered Alternatives

1. **Two-phase commit (XA) across databases:** atomic, but blocking, the coordinator is a single point of failure, poorly supported by RabbitMQ and modern drivers, and it couples service availability.
2. **Choreographed saga (each service reacts to the other's events):** no central coordinator, but the transfer flow and its compensations are spread across services and hard to follow, test and audit.
3. **Orchestrated saga in the Transfer Service + Transactional Outbox:** one explicit state machine owns the flow and compensations; each step is a local ACID transaction.
4. **Broker choice: Kafka instead of RabbitMQ:** strong ordering and replay, but heavier to operate and over-sized for command/reply traffic; the team knows RabbitMQ.

## Decision

- The **Transfer Service orchestrates** each transfer as a saga: `DebitWallet -> CreditWallet -> COMPLETED`, compensating with `ReverseDebit -> REVERSED` when the credit fails or times out after 3 attempts (10 s each). An insufficient-funds debit ends in `REJECTED`.
- Every service that changes state and emits a message writes both in **one local transaction** (`outbox_messages` table). A relay publishes pending rows to **RabbitMQ** with publisher confirms and marks them as published.
- RabbitMQ with durable **quorum queues**, manual acks, retries with backoff and a **DLQ per queue**. Delivery is at least once, so every consumer is **idempotent** (`processed_messages` + `UNIQUE(transferId, type)` in the ledger).
- `transferId` and `correlationId` travel in every message.

## Trade-offs

**Positives (what we gain)**
- Money is never lost: a debit is always followed by a credit or a reversal.
- No lost steps after crashes: the outbox makes "save state + send message" atomic.
- The whole flow is in one state machine, easy to test, observe and audit.
- Services stay available independently; Wallet outages delay transfers instead of breaking them.

**Negatives (risks / what we lose)**
- Eventual consistency: the API returns `202 Accepted` and the client polls `GET /transfers/{id}`. Mitigated with a fast saga (target < 1 s) and clear states.
- Duplicate deliveries: mitigated with idempotent consumers.
- Outbox relay lag and a stuck saga: mitigated with lag metrics, step deadlines and a timeout sweeper that resumes or compensates.
- More moving parts (relay, DLQs): mitigated by sharing the outbox and consumer code in `finova-common`.

## Final Justification

The case failure is exactly a non-atomic, multi-step money movement with no compensation. An orchestrated saga turns it into a sequence of local transactions with an explicit, automatic reversal, and the outbox removes the "crash between save and send" gap. 2PC would trade the bug for blocking and availability issues, and choreography would hide the flow that auditors and developers need to see. RabbitMQ fits command/reply messaging with DLQs and the team's experience.
