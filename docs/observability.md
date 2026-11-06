# Observability: following one request through the logs

spec.md §1: *"`transferId` (when it applies) and `correlationId` travel in every message and every log line."*

## How it works

1. The API Gateway creates a new `correlationId` (UUID) for every request; whatever the client sent is ignored (spec.md §1.5).
2. The Gateway returns it in the `X-Correlation-Id` response header, and puts it in the `correlationId` field of every error body.
3. The Gateway forwards it to the service in the `X-Correlation-Id` request header.
4. Every service puts it in the logging context (SLF4J MDC) for the whole request, and its log pattern prints it:

   ```
   2026-11-03T00:30:49.952Z  WARN 1 --- [identity-service] [nio-8080-exec-2] [b76d46ee-654b-4484-b481-39a755cd6b11] [fae888b5cb92f5f5f46a5587bd4f8f49,4e77f5a79774d381] c.c.finova.identity.api.AuthController   : Login rejected: invalid credentials
   ```

5. Alloy ships the stdout of every container to Loki with a `service` label (the compose service name).

Done in: `api-gateway` and `identity-service`.

## Finding a request

1. In Postman (or `curl -i`), copy the `X-Correlation-Id` response header.
2. Open Grafana (http://localhost:3000), **Explore**, data source **Loki**.
3. Query every service at once:

   ```logql
   {service=~".+"} |= "b76d46ee-654b-4484-b481-39a755cd6b11"
   ```

   Or only some of them: `{service=~"api-gateway|identity-service"} |= "<id>"`.

Rejections in Identity (failed login, locked account, reused refresh token, failed step-up) are logged at WARN, so
`{service="identity-service"} |= "WARN"` lists the security events. They never contain the email, password, PIN,
OTP code or tokens.

## Adding it to a service (Wallet, Transfer, Audit, Notification)

### 1. HTTP requests that come from the Gateway

Copy `services/identity-service/infrastructure/entry-points/api-rest/src/main/java/co/com/finova/identity/api/CorrelationIdFilter.java`
into the service's `api-rest` module and change the package. It reads `X-Correlation-Id`, accepts only a UUID (so a
direct call cannot forge log lines), puts it in the MDC and removes it when the request ends. Its unit test is next to
it in `src/test`.

### 2. The log pattern

In `applications/app-service/src/main/resources/application.yaml`:

```yaml
logging:
  pattern:
    correlation: "[%X{correlationId:-}] [%X{transferId:-}] [%X{traceId:-},%X{spanId:-}] "
```

This is the order Wallet, Transfer and Audit use. The Gateway and Identity leave out `transferId`: they never handle a
transfer. Keep the `[traceId,spanId]` pair as it is: Grafana's link from a log line to its trace finds that pair by its
shape (derived field in `infra/observability/grafana/provisioning/datasources/datasources.yml`), wherever it sits in
the line.

### 3. RabbitMQ messages

Every message carries `correlationId` and `transferId` in its envelope (spec.md §4.1).

- **Consumer:** put both in the MDC before handling the message and remove them afterwards:

  ```java
  MDC.put("correlationId", envelope.correlationId());
  if (envelope.transferId() != null) {
      MDC.put("transferId", envelope.transferId());
  }
  try {
      handle(envelope);
  } finally {
      MDC.remove("correlationId");
      MDC.remove("transferId");
  }
  ```

- **Producer and outbox:** copy the `correlationId` of the request or message being handled into the new envelope.
  Do not create a new one: a transfer keeps the id the Gateway gave to `POST /transfers` through the whole saga.
  The outbox row must store it, because the relay that publishes it runs on another thread, without the MDC.

### 4. Check it

Write one integration test that sends a request with `X-Correlation-Id`, captures the output with Spring Boot's
`OutputCaptureExtension`, and asserts that the log line contains `[<id>]`. `AuthApiIntegrationTest` in Identity
and `GatewayPipelineIntegrationTest` in the Gateway are examples.

## Traces

The Gateway, Identity, Wallet, Transfer and Audit send traces to Tempo through Alloy (`MANAGEMENT_OTLP_TRACING_ENDPOINT` in
docker-compose; every request is sampled). The Gateway propagates the trace to the service it calls, so one request is
one trace:

```
api-gateway       http post /auth/**     (request received)
└─ api-gateway    http post              (call to Identity)
   └─ identity-service  http post /auth/login
```

Every log line also carries `[traceId,spanId]` after the correlationId:

```
WARN 1 --- [identity-service] [nio-8080-exec-2] [b76d46ee-654b-4484-b481-39a755cd6b11] [fae888b5cb92f5f5f46a5587bd4f8f49,4e77f5a79774d381] ... : Login rejected: invalid credentials
```

Wallet and Transfer carry `traceparent` in the AMQP headers of every message they publish (never in the §4.1 envelope),
and their listeners continue the trace of the message they consume. The saga is still **several traces, not one**: each
outbox relay publishes later, on its own thread, and the outbox row does not keep the trace context. The traces of one
transfer are found by its `transfer.id` tag (below), and its log lines by its `correlationId` (spec.md §4.1).
Notification sends no traces.

```
trace 1  api-gateway        http post /transfers
         └─ transfer-service   http post /transfers
            └─ transfer-service   saga NONE -> PENDING                 (transfer.saga.transition)
trace 2  transfer-service   <exchange> send                          (relay publishes DebitWallet)
         └─ wallet-service     wallet.commands.q receive             (wallet.* tags)
trace 3  wallet-service     <exchange> send                          (relay publishes WalletDebited)
         └─ transfer-service   transfer.wallet-events.q receive
            └─ transfer-service   saga PENDING -> DEBITED
...      one trace per command and one per result, until the transfer ends
```

Alloy drops the spans of `@Scheduled` runs (`task ...`) and of `/actuator/health` and `/actuator/prometheus`
(`infra/observability/alloy/config.alloy`), and Wallet and Transfer open no observation for their scheduled runs, so the
relays and the sweeper do not bury the real traces. A sweeper step still opens its own `transfer.saga.transition` span.

### Saga tags

Observability only: the spec does not define these tags. No tag or event carries amounts, emails, tokens or contact data.

`transfer.saga.transition` (Transfer): one span per saga transition, wrapping the real work, named
`saga <from> -> <to>`. It is also a timer metric with the same low-cardinality tags.

| Tag | Cardinality | Values |
|---|---|---|
| `transfer.status.from` | low | `NONE` (creation), `PENDING`, `DEBITED`, `COMPENSATING`, ... |
| `transfer.status.to` | low | `PENDING`, `DEBITED`, `COMPLETED`, `REJECTED`, `COMPENSATING`, `REVERSED` |
| `saga.trigger` | low | `CreateTransfer`, `WalletDebited`, `DebitRejected`, `WalletCredited`, `CreditFailed`, `DebitReversed`, `PENDING_TIMEOUT`, `CREDIT_TIMEOUT`, `REVERSE_RESEND` |
| `transfer.failure_reason` | low | `INSUFFICIENT_FUNDS`, `SENDER_WALLET_NOT_FOUND`, `RECIPIENT_WALLET_NOT_FOUND`, `CREDIT_TIMEOUT` or `none` |
| `saga.credit.attempt` | low | the `CreditWallet` attempt sent (`1`..`3`) when the transfer is `DEBITED` after the step, else `none` |
| `saga.ignored` | low | `true` for a result rule 9 ignores (`from` = `to`), else `false` |
| `transfer.id`, `correlation.id` | high (span only) | UUIDs |

Every low-cardinality tag is always present (`none` until known), so all series of the timer have the same keys.

Listener spans (`... receive`): high-cardinality tags only, so the `spring.rabbit.listener` metric does not change.

| Span | Tags |
|---|---|
| Transfer, `transfer.wallet-events.q` | `transfer.id`, `message.type` |
| Wallet, `wallet.commands.q` | `transfer.id`, `message.type`, `wallet.operation` (`DEBIT`, `CREDIT`, `REVERSAL`), `wallet.result` (the result emitted), `wallet.settlement` (`CLAIMED`, `ALREADY_CREDITED`, `ALREADY_REVERSED`, `NOT_APPLICABLE`), `wallet.replayed` (`true` when `processed_messages` answered a repeated command) |

**Span events.** The INFO and WARN lines of the saga use cases are also events of the current span, with the same text
as in Loki (Transfer: `CreateTransferUseCase`, `HandleWalletResultUseCase`, `SweepTimeoutsUseCase`; Wallet:
`DebitWalletUseCase`, `CreditWalletUseCase`, `ReverseDebitUseCase`). ERROR lines are left out. In Jaeger they are the
**Logs** of the span, for example `CreditFailed: DEBITED -> COMPENSATING messageId=... correlationId=... transferId=...`.

### Finding a transfer

**Jaeger** (http://localhost:16686): pick a **Service**, put the tags in **Tags** (space-separated) and **Find Traces**.

- Every transition of a transfer: Service `transfer-service`, Tags `transfer.id=<uuid>`.
- What Wallet did with it: Service `wallet-service`, Tags `transfer.id=<uuid>`.
- Compensated transfers: Service `transfer-service`, Tags `transfer.status.to=REVERSED`.
- Ignored late results (rule 9): Service `transfer-service`, Tags `saga.ignored=true`.

**Tempo** (Grafana → Explore → Tempo, TraceQL), across every service at once:

```
{ span.transfer.id = "<uuid>" }
{ span.transfer.status.to = "REVERSED" }
{ resource.service.name = "wallet-service" && span.wallet.result = "CreditFailed" }
```

Expected `transfer.saga.transition` spans (Transfer) and listener spans (Wallet) for the Feature 4 scenarios:

| Scenario | Transfer (`from -> to`, `saga.trigger`) | Wallet (`wallet.operation` / `wallet.result` / `wallet.settlement`) |
|---|---|---|
| F4.1 completed | `NONE -> PENDING` CreateTransfer; `PENDING -> DEBITED` WalletDebited (`saga.credit.attempt=1`); `DEBITED -> COMPLETED` WalletCredited | `DEBIT` / `WalletDebited` / `NOT_APPLICABLE`; `CREDIT` / `WalletCredited` / `CLAIMED` |
| F4.2 insufficient funds | `NONE -> PENDING` CreateTransfer; `PENDING -> REJECTED` DebitRejected (`transfer.failure_reason=INSUFFICIENT_FUNDS`) | `DEBIT` / `DebitRejected` / `NOT_APPLICABLE` |
| F4.4 recipient without wallet | `NONE -> PENDING`; `PENDING -> DEBITED` (`saga.credit.attempt=1`); `DEBITED -> COMPENSATING` CreditFailed; `COMPENSATING -> REVERSED` DebitReversed (both with `transfer.failure_reason=RECIPIENT_WALLET_NOT_FOUND`) | `DEBIT` / `WalletDebited` / `NOT_APPLICABLE`; `CREDIT` / `CreditFailed` / `NOT_APPLICABLE`; `REVERSAL` / `DebitReversed` / `CLAIMED` |

### Links between logs and traces

In Grafana, a log line in **Explore → Loki** has links to its trace in **Tempo** (`traceId`) and in **Jaeger**
(`jaegerTraceId`), and a `transferId` link that shows every log line of that transfer. From a span in Tempo or Jaeger,
**Logs for this span** goes back to Loki. In the Jaeger UI, each trace has a **Log lines of this trace in Grafana
(Loki)** link (`infra/observability/jaeger/ui-config.json`). Jaeger keeps the traces in memory, so they are gone after the
container restarts.

A service adds tracing with two dependencies in `app-service`: `io.micrometer:micrometer-tracing-bridge-otel` and
`io.opentelemetry:opentelemetry-exporter-otlp`, plus the log pattern above.

## Alerts

Rules in `infra/observability/prometheus/alerts.yml`, evaluated by Prometheus every 15 s. There is no Alertmanager:
alerts are shown, not sent (no email or Slack).

- Prometheus: http://localhost:9090/alerts (state of every rule: inactive, pending, firing)
- Grafana: **Alerting** → **Alert rules** (Prometheus rules are listed under the Prometheus data source)

| Alert | Fires when | Severity |
|---|---|---|
| `MessagesInDeadLetterQueue` | Any `*.dlq` queue has a message (spec.md §4.4) | critical |
| `ServiceDown` | A service does not answer `/actuator/prometheus` for 1 minute | critical |
| `HighServerErrorRate` | More than 5% of a service's requests end in 5xx for 5 minutes | warning |
| `SecurityStoreUnavailable` | The Gateway answered 503 because Redis is unreachable (fail closed) | critical |
| `RefreshTokenReused` | Identity revoked a session because a rotated refresh token came back (spec.md F1.3) | warning |
| `LoginFailureSpike` | More than 20 failed logins in 5 minutes, across all accounts | warning |

`ServiceDown` fires for every service that is not running, so with only the Gateway and Identity up the others show as
down. That is expected until the whole system runs with `--profile apps`.

### Dashboard

**Finova - Overview** (Grafana → Dashboards → Finova) shows them in two sections:

- **Alerts**: the alerts firing now (`ALERTS{alertstate="firing"}`), colored by severity.
- **Security**: Gateway rejections by code, Identity security events (both per minute) and the transfers answered
  from Redis by idempotency in the selected range. Per-minute values can have decimals: Prometheus extrapolates `increase()`.

### Metrics behind the security alerts

| Metric | Service | Labels |
|---|---|---|
| `finova_gateway_rejections_total` | Gateway | `code` (the ErrorResponse code, e.g. `TOKEN_REVOKED`, `RATE_LIMIT_EXCEEDED`), `status` |
| `finova_gateway_idempotent_replays_total` | Gateway | none: retries answered from Redis without calling Transfer |
| `finova_identity_security_events_total` | Identity | `event`: `login_failed`, `account_locked`, `refresh_reused`, `step_up_failed` |

The counters an alert reads are registered at 0 when the service starts. A counter that first appears with value 1 has
no earlier sample, so `increase()` reads 0 and the first event after a restart would not fire the alert.

### Testing the rules

`alerts.test.yml` feeds made-up series to each rule and checks when it fires and when it does not:

```bash
cd infra/observability/prometheus
docker run --rm -v "$PWD":/w -w /w --entrypoint promtool prom/prometheus:v3.5.0 test rules alerts.test.yml
```
