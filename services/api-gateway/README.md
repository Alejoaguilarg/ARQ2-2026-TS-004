# API Gateway

The single public entry point of Finova (spec §1.4, §3.1). It authenticates every request, applies rate limits and lockouts, checks the financial session, enforces the `Idempotency-Key` of `POST /transfers` (ADR 003), routes the 10 public endpoints to their services and audits the transfer attempts it answers itself.
Java 21, Spring Boot 3.4, Spring Cloud Gateway Server MVC, Bancolombia clean-architecture scaffold, Redis, RabbitMQ.

## What it does

| | |
|---|---|
| Routes | Exactly the 10 endpoints of spec §3.1.3, by method and path. Anything else answers 404 without reaching a service |
| Authenticates | RS256 access tokens against Identity's JWKS (`kid` cache), plus the `revoked:jti` and `revoked:sid` keys in Redis (F1.4, F1.6). Injects `X-User-Id`, `X-Device-Id` and `X-User-Roles` and drops any copy the client sent |
| Limits | Per-IP limits on login and refresh, and per-user limits on the other routes (§3.1.2). Login and step-up lockouts after 5 failures (F1.5, F1.9) |
| Guards transfers | `X-Financial-Session` must be valid, unexpired and single use for one `Idempotency-Key` (F1.7 to F1.10). `Idempotency-Key`: replay, 409 or 422 per §3.1.1 (Feature 3) |
| Audits | Publishes `AttemptRecorded` to `audit.events` for every `POST /transfers` it rejects, replays or cannot route (§4.8) |
| Fails closed | Redis down: 503 `SECURITY_STORE_UNAVAILABLE`, or 503 `IDEMPOTENCY_STORE_UNAVAILABLE` on transfers. Upstream down: 502, upstream timeout: 504 |

## Request pipeline

Servlet filters, in order (`@Order` in `gateway-filters`):

| Order | Filter | Does |
|---|---|---|
| 0 | `CorrelationIdFilter` | New `X-Correlation-Id` for every request, in the MDC and in the response |
| +5 | `TransferAuditFilter` | Records the outcome of `POST /transfers` answered by the Gateway itself |
| +10 | `AuthenticationFilter` | Bearer token, revocation, role `AUDITOR` on `/audit/**`, identity headers |
| +20 | `RateLimitFilter` | Fixed one-minute windows in Redis. Per IP: `ClientAddress` trusts `X-Real-IP` only from `TRUSTED_PROXIES` |
| +30 | `LockoutFilter` | Counts `INVALID_CREDENTIALS` and `STEP_UP_FAILED` answers; 423 while locked |
| +35 | `FinancialSessionFilter` | Step 3 of §3.1 for `POST /transfers` |
| +40 | `IdempotencyFilter` | `SET NX` with `IN_PROGRESS`, stores the answer, frees the key on 5xx |
| +50 | `UpstreamErrorFilter` | Maps an unreachable or slow upstream to 502 or 504 `UPSTREAM_UNAVAILABLE` |

## Modules

```
domain/model                     AccessToken, FinancialSessionToken, IdempotencyRecord, GatewayAttempt, ports
domain/usecase                   SessionUseCase, RateLimitUseCase, LockoutUseCase, IdempotencyUseCase, RecordAttemptUseCase
infrastructure/entry-points
  gateway-filters                The filters above, GatewayResponses (ErrorResponse, spec §3.2), ClientAddress
infrastructure/driven-adapters
  jwt                            JwksTokenVerifier (Nimbus, Identity's JWKS)
  redis                          RedisSessionStore, RedisCounterStore, RedisIdempotencyStore
  rabbitmq-publisher             RabbitAttemptPublisher (publisher confirms, retries off the request thread)
applications/app-service         Spring Boot application, routes and configuration
```

## Configuration

| Variable | Default | What |
|---|---|---|
| `IDENTITY_SERVICE_URI`, `WALLET_SERVICE_URI`, `TRANSFER_SERVICE_URI`, `AUDIT_SERVICE_URI` | `http://localhost:8081` … `8084` | Upstreams |
| `JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | Identity's internal JWKS |
| `JWT_ISSUER` | `finova-identity` | Expected `iss` |
| `TRUSTED_PROXIES` | empty | Hosts whose `X-Real-IP` is trusted for per-IP limits (`frontend` in docker-compose) |
| `UPSTREAM_CONNECT_TIMEOUT`, `UPSTREAM_READ_TIMEOUT` | `2s`, `10s` | Upstream timeouts (504 when exceeded) |
| `MANAGEMENT_SERVER_PORT` | `9090` | Health and metrics port, not published by docker-compose |
| `SPRING_DATA_REDIS_HOST`, `SPRING_RABBITMQ_*` | `localhost` | Redis and the broker |

The API listens on 8080. `/actuator/health` and `/actuator/prometheus` answer only on the management port: Prometheus scrapes `api-gateway:9090` inside the network.

## Run and test

```bash
./gradlew build                      # unit tests + Testcontainers tests (Redis, RabbitMQ), coverage >= 90 %
docker compose --profile apps up -d --build api-gateway   # from the repository root, with its dependencies
```

`GatewayPipelineIntegrationTest` runs the whole pipeline against a real Redis and RabbitMQ and stub upstreams: authentication, revocation, financial session, idempotency (replay, 409, 422, key freed after a 504), routing and the management port. The Postman collections in `docs/postman/` exercise the running system through the Gateway.
