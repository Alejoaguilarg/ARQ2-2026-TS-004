# ADR 002: Orchestrated Saga with Transactional Outbox over RabbitMQ for P2P Transfers

* **Context and Problem:** Finova's current transfer flow performs a debit and a credit as separate synchronous operations. If a failure occurs between them, money can be debited without being credited, creating partial transfers. Transfer state is stored in the Transfer Service while balances belong to the Wallet Service, making a shared ACID transaction impossible. Finova also requires guarantees that state changes and the messages announcing them cannot be separated by crashes, while supporting retries, fault tolerance and service independence.

* **Considered Alternatives:**
   * **Two-Phase Commit (XA):** provides atomicity across databases but introduces blocking transactions, a coordinator single point of failure, tight coupling between services, and poor alignment with RabbitMQ and modern distributed systems.
   * **Choreographed Saga:** removes the central coordinator but distributes transfer logic and compensation rules across multiple services, making the flow harder to understand, test and audit.
   * **Kafka instead of RabbitMQ:** offers strong ordering and replay capabilities but adds operational complexity and exceeds the requirements of Finova's command/reply workflow.
   * **Orchestrated Saga with Transactional Outbox:** centralizes transfer orchestration, keeps each step as a local ACID transaction, and guarantees reliable message publication.

* **Decision:** Finova adopts an **Orchestrated Saga with Transactional Outbox** using **RabbitMQ** as the messaging backbone. The Transfer Service acts as the saga orchestrator and manages the lifecycle of each transfer (`PENDING → DEBITED → COMPLETED`). Failed debits are rejected, while failed or timed-out credits trigger automatic compensation through `ReverseDebit`, leading to `REVERSED` when necessary. Wallet processes compensation atomically to ensure a transfer is never both credited and reversed. Every service persists state changes and outbound messages in a single local transaction using an `outbox_messages` table, while a relay publishes messages to RabbitMQ with publisher confirms. RabbitMQ uses durable quorum queues, retries with backoff, dead-letter queues and at-least-once delivery. To prevent duplicate processing, all consumers are idempotent through `processed_messages` tracking and unique business keys. `transferId` and `correlationId` are propagated throughout the workflow for traceability.

* **Trade-offs:**
   * **Positives (What we gain):**
      * Money is never lost because every debit eventually results in either a successful credit or an automatic reversal.
      * The Transactional Outbox eliminates the risk of losing messages due to crashes between persistence and publication.
      * The complete transfer workflow is managed through a single state machine, simplifying testing, monitoring and auditing.
      * Services remain independently available, allowing transfer processing to resume automatically after temporary outages.
      * Reliable messaging, retries, DLQs and idempotent consumers improve overall system resilience.

   * **Negatives (Risks / What we lose):**
      * Eventual consistency replaces immediate consistency, requiring asynchronous processing and transfer status polling.
      * Duplicate message deliveries are possible due to at-least-once delivery semantics, mitigated with idempotent consumers.
      * Additional operational components are required, including the outbox relay, RabbitMQ infrastructure and DLQs.
      * Saga orchestration introduces more application complexity than synchronous request-response flows.
      * Delayed responses or timeout scenarios require compensation logic and monitoring to prevent stuck workflows.

* **Final Justification:** Finova's primary challenge is ensuring that money transfers remain correct even when failures occur between debit and credit operations. An Orchestrated Saga converts the transfer into a sequence of local transactions with explicit compensation, while the Transactional Outbox guarantees that state changes and published messages remain consistent. This combination directly solves the risk of partial transfers and lost messages without introducing the availability and coordination problems of Two-Phase Commit. RabbitMQ provides the reliability, retry mechanisms, dead-letter handling and operational simplicity needed for the project, while aligning with the team's expertise. Together, these patterns deliver the consistency, resilience, traceability and fault tolerance required for a financial transfer platform.