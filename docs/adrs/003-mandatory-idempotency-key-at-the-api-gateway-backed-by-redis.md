# ADR 003: Mandatory Idempotency-Key at the API Gateway backed by Redis

* **Context and Problem:** Finova must prevent duplicate transfers caused by client retries, browser refreshes and repeated user actions under latency. The project explicitly requires idempotency enforcement at the API Gateway. The solution must support atomic validation under concurrent requests, provide low-latency processing for every transfer, and distinguish between requests that are currently processing, already completed, or invalid because the same key was reused for different data.

* **Considered Alternatives:**
   * **Idempotency only in the Transfer Service using a database unique constraint:** prevents duplicate records but still allows duplicate requests to reach business services and does not satisfy the requirement to enforce idempotency at the Gateway.
   * **Gateway validation backed by PostgreSQL:** provides durability but introduces database contention and additional latency on every transfer request.
   * **Client-side debouncing only:** improves user experience but cannot prevent retries, requests from multiple devices or malicious repeated submissions.
   * **Gateway validation backed by Redis with database protection as defense in depth:** provides atomic, high-performance validation at the edge while preserving financial correctness through an additional persistence-layer safeguard.

* **Decision:** Finova requires a mandatory **Idempotency-Key** on every `POST /transfers` request. Validation is performed at the **Spring Cloud Gateway** using **Redis** as the primary idempotency store. The Gateway atomically validates requests using Redis and distinguishes between new requests, requests already in progress, completed requests and invalid key reuse. Duplicate requests with the same payload receive the original stored response, while requests using the same key for different payloads are rejected. If processing fails because of server-side errors, the client can safely retry with the same key. The solution also records idempotency-related events for auditing and traceability. As a second protection layer, the Transfer Service enforces a unique constraint on `(sender_user_id, idempotency_key)` so that duplicate transfers cannot be created even if an idempotency validation record is lost. Wallet operations remain idempotent through the transfer identifier, and clients disable the transfer action while a request is in progress.

* **Trade-offs:**
   * **Positives (What we gain):**
      * Duplicate requests are stopped before reaching any business service, ensuring only one transfer execution per user intent.
      * Atomic validation through Redis provides extremely fast concurrency control and scales efficiently.
      * Clients receive consistent and predictable behavior for retries, duplicate submissions and key conflicts.
      * The Gateway-level control reduces unnecessary load on business services and databases.
      * Defense-in-depth protection combines infrastructure and domain safeguards for greater financial correctness.

   * **Negatives (Risks / What we lose):**
      * Redis becomes a critical component for transfer processing and idempotency validation.
      * Additional infrastructure and operational complexity are required to manage Redis availability and persistence.
      * Idempotency records expire after a configured retention period, requiring database-level protections for long-term guarantees.
      * Gateway failures during request processing can leave requests temporarily marked as in progress until expiration or recovery.
      * More storage is required to persist request metadata and replay responses.

* **Final Justification:** Finova's primary goal is ensuring that a single transfer intent can never create multiple transfer executions, regardless of retries, latency or repeated user actions. Enforcing idempotency at the API Gateway prevents duplicate requests from entering the platform, while Redis provides the atomic and high-performance validation required at the edge. The additional database constraint in the Transfer Service creates an independent safety layer so that financial correctness does not depend on a single infrastructure component. Together, these controls guarantee exactly one transfer execution per intent, improve user experience, reduce system load and provide the reliability required for a financial platform.