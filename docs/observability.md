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
    correlation: "[%X{correlationId:-}] [%X{traceId:-},%X{spanId:-}] [%X{transferId:-}] "
```

The Gateway and Identity leave out `transferId`: they never handle a transfer. Keep `[traceId,spanId]` right after the
correlationId: Grafana's link from a log line to its trace looks for it there.

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

A trace covers one HTTP request. The saga does not continue it through RabbitMQ: the outbox relay publishes later, on
another thread, and the outbox row does not keep the trace context. Follow a transfer across Transfer, Wallet, Audit and
Notification by its `correlationId`, which every message carries (spec.md §4.1). Notification has no HTTP API, so it sends
no traces.

In Grafana, open a log line in **Explore → Loki** and click the **traceId** link to see its trace in Tempo; from a
trace, **Logs for this span** goes back to Loki. A service adds tracing with two dependencies in `app-service`:
`io.micrometer:micrometer-tracing-bridge-otel` and `io.opentelemetry:opentelemetry-exporter-otlp`, plus the log
pattern above.

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
