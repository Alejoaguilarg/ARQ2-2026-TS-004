# Observability: following one request through the logs

spec.md §1: *"`transferId` (when it applies) and `correlationId` travel in every message and every log line."*

## How it works

1. The API Gateway creates a new `correlationId` (UUID) for every request; whatever the client sent is ignored (spec.md §1.5).
2. The Gateway returns it in the `X-Correlation-Id` response header, and puts it in the `correlationId` field of every error body.
3. The Gateway forwards it to the service in the `X-Correlation-Id` request header.
4. Every service puts it in the logging context (SLF4J MDC) for the whole request, and its log pattern prints it:

   ```
   WARN 1 --- [identity-service] [XNIO-1 task-2] [5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11] c.c.f.identity.api.AuthController : Login rejected: invalid credentials
   ```

5. Alloy ships the stdout of every container to Loki with a `service` label (the compose service name).

Done in: `api-gateway` and `identity-service`.

## Finding a request

1. In Postman (or `curl -i`), copy the `X-Correlation-Id` response header.
2. Open Grafana (http://localhost:3000), **Explore**, data source **Loki**.
3. Query every service at once:

   ```logql
   {service=~".+"} |= "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11"
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
    correlation: "[%X{correlationId:-}] [%X{transferId:-}] "
```

The Gateway and Identity print only `correlationId`: they never handle a transfer.

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
