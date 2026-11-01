# ADR 004: CQRS Balance Read Model in Redis Updated by Versioned Events

* **Context and Problem:** Balance inquiries are the most frequently executed operation in Finova, occurring whenever users open the application, refresh screens or prepare a transfer. Serving these requests directly from the transactional database creates contention with critical debit and credit operations, consuming the same connections and I/O resources during peak demand. While balance displays can tolerate minor delays, transfer validation and money movement require strict consistency and cannot depend on cached data.

* **Considered Alternatives:**
   * **PostgreSQL with read replicas:** simple to implement, but replica lag can increase under load and every request still consumes database resources.
   * **Cache-aside with TTL expiration:** easy to adopt, but stale balances may remain visible for the entire cache lifetime and invalidation races can reintroduce outdated data.
   * **CQRS read model in Redis updated through versioned events:** separates read and write workloads, provides high-performance balance queries, and guarantees that only newer balance updates are applied.

* **Decision:** Finova adopts a **CQRS Balance Read Model** stored in **Redis** and updated through **versioned domain events** emitted by the Wallet Service. Every balance-changing operation, including debits, credits and reversals, generates a `BalanceChanged` event that is published through the Transactional Outbox pattern. A projection consumer updates Redis using version-based compare-and-set logic, ensuring that only events with a newer version can modify the stored balance. The `GET /wallets/me/balance` endpoint reads directly from Redis to provide extremely fast responses. In the event of a cache miss, the balance is loaded from PostgreSQL and written back to Redis using the same version validation mechanism. Redis is used exclusively as a read model; all transfer validation, balance checks and money movement continue to execute against PostgreSQL, which remains the system of record and source of financial truth.

* **Trade-offs:**
   * **Positives (What we gain):**
      * Sub-millisecond balance queries without impacting the transactional database.
      * Independent scaling of read and write workloads, reducing database contention during peak traffic.
      * Safe handling of duplicate, delayed and out-of-order events through monotonic version validation.
      * Improved application responsiveness and user experience for balance inquiries.
      * PostgreSQL remains focused on transactional operations that require strict consistency.

   * **Negatives (Risks / What we lose):**
      * Eventual consistency means displayed balances may lag slightly behind recent transactions.
      * An additional projection component must be maintained and monitored.
      * Redis introduces infrastructure, operational and memory-management considerations.
      * Cache reconstruction and recovery processes are required if projections become unavailable.
      * Availability of balance reads depends on Redis, although persistence and replication mitigate this risk.

* **Final Justification:** Finova requires highly responsive balance queries while preserving strict financial correctness for debits, credits and transfer validation. A CQRS read model allows Redis to serve high-volume balance requests with extremely low latency while PostgreSQL remains the authoritative source for all money movement. Versioned events ensure that balance projections can only move forward, protecting the read model against duplicate and out-of-order message delivery. This architecture improves scalability, reduces database contention and delivers a faster user experience without compromising financial integrity.