# Notification Service — Finova

Sends the outcome of every transfer to the users (push, email or SMS) through the external provider. It is never in the critical path: if it goes down, transfers keep working and the events wait in the queue (spec F5.8).

Contract: `docs/spec.md` §1.1, §2 (`RecipientContact`, `NotificationLog`), §4.2–§4.5 and §4.9.

## What it does

| Event (published by Transfer) | Who is notified (§4.9) |
|---|---|
| `TransferCompleted` | Sender and recipient |
| `TransferRejected` | Sender |
| `TransferReversed` | Sender |

1. Consumes `notification.transfer-events.q` (exchange `transfer.events`).
2. For each user to notify:
    - skips it if its notification is already `SENT`;
    - looks up the contact in `recipient_contacts`;
    - sends through the user's `preferredChannel`;
    - stores the result in `notification_log` (`SENT` or `FAILED`, `attempts + 1`).
3. Acks the message, or asks for a retry if something failed.

| Situation | What happens to the message |
|---|---|
| Everything sent | ack |
| Provider down, timeout or error answer | reject → `.retry` (5 s) → back to the queue. After 3 retries (4 attempts in total) → DLQ |
| Invalid JSON, no envelope, unknown type or missing fields | straight to the DLQ |
| User without contact data, or without an address for its channel | straight to the DLQ |

A message moved to the DLQ carries the header `x-notification-error` with the cause.

## Architecture (hexagonal, same Bancolombia layout as the other services)

| Module | Contents |
|---|---|
| `domain/model` | `TransferOutcome`, `RecipientContact`, `NotificationLog`, `Notification`, `NotificationMessages` and the ports: `RecipientContactRepository`, `NotificationLogRepository`, `NotificationProvider` |
| `domain/usecase` | `NotifyTransferOutcomeUseCase`: the business rules, without Spring |
| `infrastructure/entry-points/rabbitmq-listener` | Driving adapter: consumes the queue, ack, retries and DLQ |
| `infrastructure/driven-adapters/jpa-repository` | PostgreSQL (`notification_db`) and Flyway migrations |
| `infrastructure/driven-adapters/rest-consumer` | HTTP provider: `POST /push`, `/email`, `/sms` (WireMock in Wave 2) |
| `infrastructure/driven-adapters/email-sender` | Real email through SMTP (optional, off by default) |
| `applications/app-service` | `MainApplication`, configuration and composition of the adapters |

`NotificationProviderConfig` picks the adapter for each channel. By default every channel goes to the HTTP provider. With `ADAPTERS_EMAIL_ENABLED=true`, EMAIL goes through SMTP and PUSH/SMS keep going to the HTTP provider. The use case never changes.

`ArchitectureTest` (ArchUnit) checks that the domain depends on neither frameworks nor adapters.

## Design decisions (not defined by the spec)

1. **Send first, record afterwards,** without keeping a transaction open while the provider answers. If the service stops between both steps, that user may receive the notification twice (at-least-once delivery). To mitigate it, the provider receives `Idempotency-Key: notificationId`.
2. **Missing contact, or no address for its channel** → DLQ. No other channel is tried, because the spec defines no fallback channel.
3. **"3 retries" = 4 attempts in total.**
4. **Body sent to the provider:** `{notificationId, channel, to, subject, message, eventType, transferId, amount, currency, reason}`.

## Run locally (IntelliJ)

Requirements: JDK 21, Docker Desktop and a branch with the team's `docker-compose.yml`.

**1. Database and provider mock.** At the repo root, create a `docker-compose.override.yml` (not committed: add it to `.git/info/exclude`):

```yaml
services:
  notification-db:
    ports:
      - "127.0.0.1:5432:5432"
  notification-provider-mock:
    ports:
      - "127.0.0.1:8089:8080"
```

Then start them:

```
docker compose up -d notification-db notification-provider-mock
```

**2. Environment variables in IntelliJ** (Run → Edit Configurations → run configuration of `MainApplication` → Environment variables), to use the CloudAMQP broker:

```
SPRING_RABBITMQ_HOST=<CloudAMQP host>
SPRING_RABBITMQ_PORT=5671
SPRING_RABBITMQ_SSL_ENABLED=true
SPRING_RABBITMQ_VIRTUAL_HOST=<vhost>
SPRING_RABBITMQ_USERNAME=<user>
SPRING_RABBITMQ_PASSWORD=<password>
```

The database (`localhost:5432`, user `notification`) and the provider (`http://localhost:8089`) are already the defaults in `application.yaml`.

**3. Start `MainApplication`.** Flyway creates the tables and loads the demo contacts. To check:

```
docker compose exec notification-db psql -U notification -d notification_db -c "select email, preferred_channel from recipient_contacts;"
```

## Real email (Gmail)

1. In a test Gmail account: turn on 2-Step Verification and create an **app password**.
2. Add these variables in IntelliJ:

```
ADAPTERS_EMAIL_ENABLED=true
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=<test account>@gmail.com
SPRING_MAIL_PASSWORD=<app password>
```

3. Point Ana and Beto (Identity's demo users) to your inbox, **only in your local database** (the repo is public). Gmail delivers `account+anything@gmail.com` to the same inbox:

```sql
UPDATE recipient_contacts SET email = '<account>+ana@gmail.com'  WHERE user_id = '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c';
UPDATE recipient_contacts SET email = '<account>+beto@gmail.com' WHERE user_id = '2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f';
```

If `R__seed_recipient_contacts.sql` changes, Flyway runs it again and restores the demo emails.

## Test without Transfer: sample messages

**With Postman (recommended):** import `postman/notification-service.postman_collection.json`. It publishes each message in `samples/` to `transfer.events` through the RabbitMQ HTTP API and checks that it was routed to a queue. Run everything at once with the *Collection Runner*.

- The variables default to the local broker of the compose (`http://localhost:15672`, vhost `%2F`, user `finova`).
- For CloudAMQP, change `rabbitUrl` (`https://<CloudAMQP host>`), `vhost`, `rabbitUser` and `rabbitPassword` in the collection's **Variables** tab. Values you type stay local to your Postman unless you share them, so the password never reaches the repo.
- To repeat the tests from scratch:
  ```
  docker compose exec notification-db psql -U notification -d notification_db -c "TRUNCATE notification_log;"
  ```

**By hand:** in the RabbitMQ console, **Exchanges → `transfer.events` → Publish message**.
- **Routing key:** `transfer.completed`, `transfer.rejected` or `transfer.reversed`.
- **Properties:** `content_type` = `application/json`, `message_id` = the file's `messageId`.
- **Payload:** the content of the file in `samples/`.

| File | What it shows |
|---|---|
| `transfer-completed.json` | Ana transfers to Beto: both are notified (EMAIL) |
| `transfer-rejected.json` | Only Ana is notified, with the reason |
| `transfer-reversed.json` | Only Ana is notified: the money was returned |
| `transfer-completed-sms-push.json` | SMS and PUSH channels (provider mock) |
| `transfer-completed-falla.json` | Ana ends `SENT`. The "falla" user gets 503 from the mock (`infra/notification-provider-mock/mappings/notifications-falla.json`), is retried every 5 s and ends in `notification.transfer-events.q.dlq` with `attempts = 4` |
| `mensaje-invalido.txt` | A body that is not JSON: straight to the DLQ |

To see the result:

```
docker compose exec notification-db psql -U notification -d notification_db -c "select user_id, event_type, channel, status, attempts from notification_log order by created_at;"
```

## Tests

```
./gradlew test -x pitest        (Windows: .\gradlew.bat test -x pitest)
```

- Domain and use case: unit tests without Spring, with in-memory adapters.
- Adapters: listener (ack, retry, DLQ), HTTP provider (`MockRestServiceServer`), SMTP (mock) and JPA.
- `JpaAdaptersIntegrationTest` runs the migrations on a real PostgreSQL 16 with Testcontainers. It needs Docker running; otherwise it is skipped.
- Without `-x pitest`, mutation tests run too (slower), as in the other services.
- The repo CI (`.github/workflows/ci.yml`) runs `./gradlew build` and requires **line coverage ≥ 90 %** across all modules except `app-service`. Each module writes its report to `build/reports/jacocoHtml/index.html`.

## With the whole system (Docker Compose)

`docker compose --profile apps up -d --build` builds this service with `deployment/Dockerfile`. The compose already injects the database, RabbitMQ and `NOTIFICATION_PROVIDER_URI`.

Optional settings go in a `.env` file at the repo root (a copy of `.env.example`; git-ignored):

| For | Variables in `.env` |
|---|---|
| Real email (Gmail) | `NOTIFICATION_EMAIL_ENABLED=true`, `NOTIFICATION_SMTP_USER`, `NOTIFICATION_SMTP_PASSWORD` (app password) |
| CloudAMQP instead of the local broker | `RABBITMQ_HOST`, `RABBITMQ_PORT=5671`, `RABBITMQ_VHOST`, `RABBITMQ_SSL_ENABLED=true`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` |

Without a `.env`, everything works with the local broker and the provider mock.