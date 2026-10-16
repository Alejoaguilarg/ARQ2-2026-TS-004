# ADR 007: Observability with Prometheus, Loki, Tempo and Grafana (OpenTelemetry)

- **Status:** Accepted
- **Date:** 2026-10-16
- **Features:** supports 4 (diagnosing stuck or compensated sagas) and 5 (tracing every attempt); required by the Wave 2 monitoring stack

## Context and Problem

A transfer crosses the Gateway, Transfer, RabbitMQ and Wallet asynchronously. When a transfer ends `REVERSED` or stays `PENDING`, the team must see in one place what happened, in which service and how long each step took, without opening the logs of 5 containers by hand. Wave 2 requires a monitoring stack (Prometheus, Loki, Grafana) for container metrics and logs, and recommends distributed tracing. Everything must be open source, free and run in Docker Compose.

## Considered Alternatives

1. **Logs only (`docker logs`) per container:** zero setup, but no metrics, no search across services and no way to follow a transfer end to end.
2. **ELK (Elasticsearch, Logstash, Kibana) + Jaeger:** mature, but Elasticsearch is memory-heavy for a laptop and adds a second UI for traces.
3. **Grafana stack: Prometheus (metrics) + Loki (logs) + Tempo (traces) + Grafana (UI), with Grafana Alloy as collector and OpenTelemetry for instrumentation.**
4. **Commercial SaaS (Datadog, New Relic):** best UX, but paid and outside the course's open-source requirement.

## Decision

- **Metrics:** every Spring Boot service and the Gateway expose `/actuator/prometheus` (Micrometer). Prometheus scrapes them every 15 s, plus the RabbitMQ and Redis exporters.
- **Logs:** services write structured JSON logs to stdout with `traceId`, `spanId`, `correlationId` and `transferId`. Grafana Alloy collects the Docker container logs and pushes them to Loki.
- **Traces:** the OpenTelemetry Java agent instruments HTTP, JDBC, Redis and RabbitMQ calls and sends OTLP to Alloy, which forwards them to Tempo. Trace context travels in HTTP and AMQP headers, so one transfer is one trace across services.
- **Grafana** is the single UI with three data sources and links between them (log line → trace → metrics).
- **Key dashboards and alerts:** transfers by final state, saga duration p95, compensations per minute, idempotency rejections (409/422), balance read latency p99 and cache hit ratio, outbox lag, DLQ depth, 5xx rate.

## Trade-offs

**Positives (what we gain)**
- One click from an error log to the full trace of that transfer.
- Saga and outbox problems become visible (outbox lag, DLQ depth, compensation rate).
- 100 % open source and free; runs in Docker Compose; one UI.

**Negatives (risks / what we lose)**
- Five more containers: mitigated with small retention (Loki/Tempo 7 days) and resource limits in Compose.
- Instrumentation overhead: the agent is automatic; sampling can be lowered if needed (100 % in the demo).
- Sensitive data in logs: logs never include PINs, tokens or full account data; amounts and IDs only.

## Final Justification

Finova's failures happen between services and asynchronously, which is exactly what metrics alone or per-container logs cannot show. The Grafana stack with OpenTelemetry satisfies the Wave 2 requirement, adds the recommended distributed tracing at no cost, and lets the team prove in the demo that a failed credit was compensated, step by step, in a single trace.
