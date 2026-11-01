# ADR 005: Append-only, Hash-chained Audit Log Fed Asynchronously

* Context and Problem: Finova must prove what happened to every transfer attempt, including rejected and duplicate ones, and the record must be impossible to modify silently. Auditing must not add latency to the transfer path or block it when the audit store is down.
* Considered Alternatives:
   * Audit columns in each service’s own tables: no single trail, rows can be updated, and rejected attempts at the Gateway would be lost.
   * Event store / Kafka log as the audit record: naturally append-only, but retention, querying by user or date, and compliance reporting are harder, and it adds Kafka to the stack.
   * Dedicated Audit Service with an append-only PostgreSQL table, insert-only privileges, triggers that reject UPDATE/DELETE, and SHA-256 hash chaining, fed from audit.events.
   * External immutable storage (WORM object storage, ledger database): strongest immutability, but outside the open-source, Docker Compose scope of the course.
* Decision:
   * Gateway, Transfer and Wallet publish AttemptRecorded for every attempt and outcome (accepted, rejected, duplicate, failed, applied, completed and compensated) to the audit.events exchange.
   * The Audit Service stores them in audit_records (PostgreSQL): the service role has only INSERT and SELECT; a trigger rejects UPDATE, DELETE and TRUNCATE.
   * Audit consumes audit.events with a single active consumer, so records are inserted in one total order; each record receives a sequence number (seq), which makes prevHash unambiguous.
   * Each row stores prevHash and hash = SHA-256(prevHash || canonicalJson(row)). GET /audit/verify recomputes the chain and returns valid: false with the first broken seq if a record was altered. Duplicate events are ignored through UNIQUE(eventId).
   * Monthly partitioning for retention, and WAL archiving for backup and point-in-time recovery.
* Trade-offs:
   * Positives (What we gain):
      * One complete, queryable trail of every attempt, including those stopped at the Gateway.
      * Tampering is prevented by privileges and detected by the hash chain.
      * Zero latency on the transfer path; if Audit is down, events wait in the durable queue.
   * Negatives (Risks / What we lose):
      * Asynchronous: a record appears milliseconds after the attempt. Mitigated because the queue is durable and producers use the outbox (Transfer, Wallet).
      * The Gateway has no outbox; mitigated with local retry and because the Transfer Service also audits every accepted request.
      * A DBA with superuser rights could still rewrite rows; the hash chain makes it detectable, and periodic anchoring of the last hash outside the database is planned.
      * A single active consumer limits audit throughput: acceptable because auditing is off the critical path and the durable queue absorbs peaks.
* Final Justification: It satisfies “append-only for every attempt” with standard open-source tools, keeps auditing off the critical path, and gives compliance both prevention (no update path) and detection (hash chain).
