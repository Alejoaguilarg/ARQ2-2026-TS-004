# Wallet Service

The Wallet Service owns every user's balance in the Finova P2P wallet. It applies the money movements of a
transfer saga (debit, credit, reversal) and answers the balance read. `docs/spec.md` is the contract; this README
only explains how the service implements it and how to run it.

- **Stack:** Java 21, Spring Boot 3.4, Gradle 8.11.1, PostgreSQL 16 (`wallet_db`), Redis 7, RabbitMQ.
- **Owns:** `wallet_db` (wallets, append-only ledger, processed messages, transfer settlements, outbox) and the
  Redis keys `balance:{userId}`.
- **Talks to:** Transfer (commands in, events out over RabbitMQ), Audit (`AttemptRecorded`) and the Gateway
  (`GET /wallets/me/balance`).

## Architecture

Hexagonal (ports and adapters, ADR 001), generated with the Bancolombia clean-architecture Gradle plugin. The
`validateStructure` task runs on every build and fails if a layer depends on the wrong one.

| Module | Layer | Contents |
|---|---|---|
| `domain/model` | Domain | Entities, value objects (`Money`), messages and ports (`gateways`). No Spring. |
| `domain/usecase` | Domain | Debit, credit, reversal, balance read, balance projection, outbox relay and cache rebuild. No Spring. |
| `driven-adapters/jpa-repository` | Infrastructure | PostgreSQL through JDBC, Flyway migrations (`db/migration`). |
| `driven-adapters/redis` | Infrastructure | Balance read model with the Lua compare-and-set (§2.1). |
| `driven-adapters/rabbitmq-publisher` | Infrastructure | Publishes outbox rows with publisher confirms. |
| `entry-points/api-rest` | Infrastructure | `GET /wallets/me/balance`, error model, `CorrelationIdFilter`. |
| `entry-points/rabbitmq-consumer` | Infrastructure | Listeners for `wallet.commands.q` and `wallet.balance-projection.q`, retries and DLQ. |
| `entry-points/outbox-relay` | Infrastructure | Scheduler that runs the outbox relay. |
| `entry-points/balance-rebuild` | Infrastructure | Scheduler that rebuilds the balance cache. |
| `helpers/message-json` | Infrastructure | JSON reader and writer for the §4.1 envelope and the §4.4 payloads. |
| `applications/app-service` | Application | Spring Boot main class, configuration and end-to-end scenario tests. |

Use cases are plain classes; every class whose name ends in `UseCase` is registered as a bean automatically.

## HTTP API

`GET /wallets/me/balance` (spec §3.7). The Gateway authenticates the user and forwards the request with:

| Header | Meaning |
|---|---|
| `X-User-Id` | The user (token `sub`). Required; the Gateway always injects it (§1.5). |
| `X-Correlation-Id` | Injected by the Gateway (§1.5). Only a UUID is accepted; it is logged on every line of the request. |

The balance is read from Redis. On a cache miss, or when Redis fails or does not answer within 200 ms, it is read
from `wallet_db` and written back to Redis with the compare-and-set (F2.6). The Redis value never authorizes a
debit.

| Status | Code |
|---|---|
| `200` | `walletId`, `available`, `currency`, `version`, `asOf` |
| `404` | `WALLET_NOT_FOUND` |
| `500` | `INTERNAL_ERROR` (also when `X-User-Id` is missing or is not a UUID: a Gateway fault) |

The other statuses of §3.7 (`401`, `429`, `502`/`504`, `503`) are produced by the Gateway, not by this service.

## Messaging

The service only consumes from and publishes to the topology of spec §4.2. **It never declares queues, exchanges
or bindings**: the topology comes from `infra/rabbitmq/definitions.json` locally and from the team account in the
shared environment.

| Direction | Exchange / routing key | Queue | Messages |
|---|---|---|---|
| In | `wallet.commands` / `debit`, `credit`, `reverse` | `wallet.commands.q` | `DebitWallet`, `CreditWallet`, `ReverseDebit` |
| Out | `wallet.events` / `wallet.debited`, `wallet.debit-rejected`, `wallet.credited`, `wallet.credit-failed`, `wallet.debit-reversed` | `transfer.wallet-events.q` | Saga replies to Transfer |
| Out and in | `wallet.events` / `wallet.balance-changed` | `wallet.balance-projection.q` | `BalanceChanged`, projected into Redis |
| Out | `audit.events` / `audit.attempt` | `audit.attempts.q` | `AttemptRecorded` |

- Every message carries the §4.1 envelope and leaves through the **transactional outbox**: the relay publishes
  pending rows every 100 ms in batches of 100, with publisher confirms (decision S3).
- A row that fails to publish (for example, an exchange or a permission missing in the broker) logs an ERROR with
  its `messageId`, records the attempt, and is retried after 1 s, 2 s, 4 s… up to 5 min. It never blocks the rows
  after it and is never dropped (`attempts`, `next_attempt_at` and `last_error` in `outbox_messages`, migration V3).
- Consumers use manual acks. A technical error rejects the delivery without requeue; the queue sends it to its
  `.retry` queue (5 s) and back. After 3 retries (4 deliveries) the message goes to the `.dlq` (decision S1).
- Invalid JSON, an unknown `type` or a contract violation goes straight to the `.dlq` without retries
  (decisions S2, S5). Unknown fields are ignored (decision S4).

## Money rules (spec §4.7)

- Each command runs in **one local transaction**: balance change, ledger entry, `processed_messages` row with its
  stored result, and the outbox rows.
- **Debit** is a single `UPDATE ... WHERE balance >= :amount RETURNING`; the balance is never read and then written.
- **Credit and reversal** are decided by `transfer_settlements`, so a transfer is either credited or reversed,
  never both.
- A **repeated command** is not applied again: the stored result is republished with a new `messageId`.
- The **ledger is append-only**: a trigger rejects `UPDATE`, `DELETE` and `TRUNCATE`.
- Money is `NUMERIC(19,2)` in the database and a string with two decimals in JSON (§1.5). No `double` or `float`.

## Configuration

Everything comes from environment variables; nothing secret is stored in the repository. Credentials come from
the root `.env` file (see `.env.example`).

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | set by the compose | `wallet_db` connection (`WALLET_DB_USER`, `WALLET_DB_PASSWORD` in `.env`) |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | `localhost`, `6379` | Redis connection |
| `SPRING_DATA_REDIS_TIMEOUT`, `SPRING_DATA_REDIS_CONNECT_TIMEOUT` | `200ms` | After this the balance read falls back to `wallet_db` |
| `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_VIRTUAL_HOST`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`, `SPRING_RABBITMQ_SSL_ENABLED` | the local `rabbitmq` container, vhost `/`, no TLS | RabbitMQ connection. The compose fills them from `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` and `RABBITMQ_SSL_ENABLED` in `.env` (see [Cloud broker](#cloud-broker-optional)) |
| `ADAPTERS_RABBITMQ_MAX_RETRIES` | `3` | Retries before the `.dlq` (S1) |
| `ADAPTERS_RABBITMQ_CONFIRM_TIMEOUT` | `5s` | Wait for a publisher confirm |
| `ADAPTERS_OUTBOX_RELAY_ENABLED`, `ADAPTERS_OUTBOX_RELAY_INTERVAL`, `ADAPTERS_OUTBOX_BATCH_SIZE` | `true`, `100ms`, `100` | Outbox relay (S3) |
| `ADAPTERS_OUTBOX_RETRY_INITIAL_DELAY`, `ADAPTERS_OUTBOX_RETRY_MAX_DELAY` | `1s`, `5m` | Wait before retrying a row that failed to publish, doubled after each failure up to the maximum |
| `ADAPTERS_BALANCE_REBUILD_ENABLED`, `ADAPTERS_BALANCE_REBUILD_INTERVAL`, `ADAPTERS_BALANCE_REBUILD_PAGE_SIZE` | `true`, `5m`, `500` | Balance cache rebuild job (S6) |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | set by the compose (`x-app-env`): Alloy, every request | Where traces go; without an endpoint nothing is exported |
| `MANAGEMENT_TRACING_ENABLED` | `true` | `false` turns tracing off |
| `SPRING_RABBITMQ_TEMPLATE_OBSERVATION_ENABLED`, `SPRING_RABBITMQ_LISTENER_SIMPLE_OBSERVATION_ENABLED` | `true` | Trace context in the AMQP headers of published and consumed messages |

## Running it

From the repository root, with Docker Desktop running. These five services are enough for the Wallet:

```bash
docker compose --profile apps up -d --build wallet-db redis rabbitmq rabbitmq-init wallet-service
docker compose ps
```

Flyway's `V2__demo_wallets.sql` provisions the wallets of the three Identity demo users (spec §1.6); the rebuild
job caches them in Redis at startup.

`rabbitmq-init` loads `infra/rabbitmq/definitions.json` and exits. The Wallet port is not published to the host;
check it from inside the container:

```bash
docker compose exec -T wallet-service wget -qO- http://localhost:8080/actuator/health
```

### Cloud broker (optional)

**The local broker is the default, and it is the setup for the delivery.** Without the `RABBITMQ_*` connection
variables below in `.env`, the compose connects every service to the local `rabbitmq` container, whose topology
`rabbitmq-init` loads from `infra/rabbitmq/definitions.json`.

To run against the team's cloud broker instead, set these in your own `.env` (never in a committed file; see the
commented lines in `.env.example`) and start the stack as above:

| Variable in `.env` | Value |
|---|---|
| `RABBITMQ_HOST` | The broker host |
| `RABBITMQ_PORT` | The TLS port of the broker (usually `5671`) |
| `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | The broker credentials |
| `RABBITMQ_VHOST` | The broker vhost (on shared cloud plans it is usually not `/`) |
| `RABBITMQ_SSL_ENABLED` | `true` |

- The service never declares queues, exchanges or bindings. The topology of spec §4.2 (quorum queues, `.retry` and
  `.dlq` queues) must already exist in that vhost; if a queue is missing, the service fails at startup.
- **Only one machine may run each service against the cloud broker at a time.** Two Wallets on the same vhost
  compete for `wallet.commands.q`, each with its own `wallet_db`, so commands would be applied against the wrong
  database (for example, a debit answered with `SENDER_WALLET_NOT_FOUND`).
- Spring AMQP logs the connection target (user, host, port and vhost, never the password) when it connects.
- The tests never use the cloud broker: they run against a local RabbitMQ in Testcontainers.

## Observability

- **Logs:** every line carries `[correlationId] [transferId] [traceId,spanId]` after the thread name (empty brackets
  when they do not apply). HTTP requests take the id from `X-Correlation-Id`; message consumers take both ids from
  the envelope; each run of the rebuild job opens its own `correlationId`. Grafana links the `traceId` to Tempo.
- **Traces:** sent over OTLP to Alloy, which forwards them to Tempo. A request from the Gateway continues the
  Gateway's trace (`traceparent` header). Published messages carry `traceparent` in their AMQP headers (never in the
  §4.1 envelope) and the listeners continue it. The outbox relay publishes on its own thread, so a command and its
  reply are separate traces; the `correlationId` links the whole saga. Scheduled runs (relay, rebuild job) are not
  traced. If Alloy or Tempo is down, spans are dropped in the background and requests are not slowed down.
- **Metrics:** `/actuator/prometheus` (HTTP latency histograms, JVM, HikariCP, RabbitMQ).
- **Health:** `/actuator/health` (with `liveness` and `readiness` groups) checks PostgreSQL and RabbitMQ. Redis is
  left out on purpose (decision S7): with Redis down the balance is still answered from `wallet_db` and no money
  movement needs Redis, so the service stays healthy. A Redis outage shows in the `redis` container health check
  and in the WARN line of the fallback.
- **Balance cache rebuild (S6):** at startup and then every 5 minutes, the job walks `wallets` in pages and writes
  each balance with the compare-and-set, so it never overwrites a newer version (F2.4) and it repairs entries left
  stale by a dead-lettered `BalanceChanged`.

## Build and tests

Run from `services/wallet-service` with JDK 21 in `JAVA_HOME` (Gradle 8.11.1 does not run on newer JDKs):

```bash
./gradlew build --no-parallel --rerun-tasks      # compile, validateStructure, all tests, JaCoCo
./gradlew test --tests "<TestClass>"             # a single test class
./gradlew pitest                                 # mutation testing of domain/model and domain/usecase
```

- Integration and scenario tests use Testcontainers: PostgreSQL 16, Redis 7 and RabbitMQ loaded with
  `infra/rabbitmq/definitions.json`. Docker must be running. No H2 and no mocked database for money rules.
- `--rerun-tasks` forces the tests to run; without it Gradle may reuse old results.
- Scenario tests are named after the spec scenario they cover, for example `F4_9_concurrentDebitsNeverOverdraw`.
- Coverage gate (as in CI, from the repository root after the build):
  `python .github/scripts/coverage.py services/wallet-service 90` (line coverage, `app-service` excluded).

## Spec deviations and open items

Decisions that the code already follows and that will be written into the spec together:

| Id | Spec | Decision |
|---|---|---|
| S1 | §4.2, §4.5 | The retry limit counts retries after the first delivery (3 = 4 deliveries), from `x-death` of the main queue. |
| S2 | §4.2 | Non-retryable messages (invalid JSON, unknown type, contract violation) go straight to the `.dlq`. |
| S3 | §1.4, ADR 002 | The outbox relay runs every 100 ms in batches of 100, with `FOR UPDATE SKIP LOCKED`. |
| S4 | §4.1, §4.4 | Incoming messages ignore unknown fields and validate the required ones. |
| S5 | §4.1, §4.4 | A command whose envelope `transferId` differs from the payload's is invalid. |
| S6 | §4.5, ADR 004 | The cache rebuild job runs at startup and every 5 minutes; configurable and can be turned off. |
| S7 | §1.3, F2.6 | `/actuator/health` does not include Redis. |
