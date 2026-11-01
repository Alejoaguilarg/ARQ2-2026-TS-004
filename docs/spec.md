# Finova P2P Virtual Wallet — Contract Specification (OpenSpec)

| Version | Date | Status |
|---|---|---|
| 3.2.0 | 2026-10-19 | Aligned with TS-004 (sections 2–4, ADRs 001–006) and the C4 Container Diagram |

This file is the contract that Wave 2 code is validated against. It follows the structure required by section 5 of the Tech Spec template:

1. Overview: services and the responsibility of each one
2. Domain schemas
3. REST APIs
4. Asynchronous events and integration contracts
5. Requirements and scenarios (WHEN / THEN) for the 5 mandatory features

Sources: TS-004 and its ADRs (001 Hexagonal + Spring Boot, 002 Orchestrated Saga + Outbox, 003 Idempotency at the Gateway, 004 CQRS balance, 005 Hash-chained audit, 006 PostgreSQL per service) and `docs/assets/c4-containers.png`.

---

## 1. Overview

### 1.1 Services and responsibilities

| Container | Technology | Responsibility | Data store |
|---|---|---|---|
| **API Gateway** | Spring Cloud Gateway | Single entry point. Validates the JWT with Identity's JWKS and checks its revocation, applies rate limiting and lockout, verifies the financial session on `POST /transfers`, runs the idempotency pre-check, injects `X-Correlation-Id`, routes, and publishes every rejected, duplicate or failed transfer attempt to `audit.events`. | Redis |
| **Identity Service** | Java 21, Spring Boot | Users, devices and sessions; login (argon2 password hashes); 15-minute access tokens; rotating, device-bound 7-day refresh tokens (session revocation on reuse); financial session through step-up (PIN or OTP); JWKS. Owner of user contact data. | `identity_db` + Redis (revoked JTIs and sessions) |
| **Wallet Service** | Java 21, Spring Boot | Wallet aggregate and ledger; conditional atomic debit, credit and reversal, idempotent per `transferId`; settles each debited transfer exactly once (credit or reversal); maintains the balance read model in Redis; serves the balance endpoint. | `wallet_db` + Redis (balance read model) |
| **Transfer Service** | Java 21, Spring Boot | Transfer aggregate and state machine, saga orchestrator (debit → credit → complete, or compensate), transactional outbox, idempotency-key uniqueness as defense in depth. | `transfer_db` |
| **Audit Service** | Java 21, Spring Boot | Stores every transfer attempt and outcome, insert-only and SHA-256 hash-chained; audit queries and chain verification. | `audit_db` |
| **Notification Service** | Java 21, Spring Boot | Sends push, email or SMS from transfer outcome events through the external provider, using the recipients' contact data provisioned from Identity. Never in the critical path. | `notification_db` |
| **Email / SMS / Push Provider** | External system (mock in Wave 2) | Delivers notifications. Only Notification talks to it. | — |

### 1.2 Clients

- **Mobile/Web App** (Wallet User): generates one `Idempotency-Key` per transfer intent and reuses it on every retry, sends `Authorization: Bearer` and `X-Financial-Session`, disables the button while the request is in flight, and follows the transfer at `GET /transfers/{transferId}`.
- **Admin Dashboard** (Operations/Compliance Analyst, role `AUDITOR`): queries audit records and verifies the hash chain.

Both clients talk only to the API Gateway over HTTPS/JSON.

### 1.3 Infrastructure

| Component | Technology | Use |
|---|---|---|
| Message broker | RabbitMQ (durable quorum queues, publisher confirms, manual acks, one retry queue and one DLQ per queue) | Saga commands and replies, domain events, audit events |
| Cache / fast store | Redis 7 (AOF + replica) | Idempotency records, revoked JTIs and sessions, consumed financial sessions, rate-limit and lockout counters, balance read model |
| Databases | PostgreSQL 16, one per service (Flyway migrations) | ACID state, outbox, ledger, audit |
| Observability | Prometheus, Loki, Grafana | Metrics and logs from every container; `correlationId` links one transfer across services |

### 1.4 Integration rules

- Only the API Gateway is exposed outside the Docker network. Services trust the identity headers in §1.5 only because they are reachable exclusively through the Gateway.
- No service reads another service's database. No service calls another over HTTP to move money: integration is only client → Gateway → service (REST) and service ↔ service through RabbitMQ.
- Every state change that emits a message writes it to `outbox_messages` in the same local transaction (Transfer, Wallet). A relay publishes pending rows with publisher confirms and marks them as published. The Gateway has no outbox: it publishes audit events directly, with local retry.
- Delivery is at-least-once, so every consumer is idempotent.
- `transferId` (when it applies) and `correlationId` travel in every message and every log line.

### 1.5 Conventions

| Item | Convention |
|---|---|
| Money | `amount` is a decimal string with exactly 2 decimals (`"150000.00"`), stored as `NUMERIC(19,2)`, always > 0 |
| Currency | ISO 4217; only `COP` |
| IDs | UUID v4 |
| Timestamps | RFC 3339, UTC |
| Identity headers forwarded by the Gateway | `X-User-Id`, `X-Device-Id`, `X-User-Roles`, `X-Correlation-Id` (any value sent by the client is stripped) |
| Errors | `ErrorResponse` (§3.2) |

### 1.6 Scope

- Every user in Identity has exactly one wallet in a single currency (COP).
- Out of scope for this design: user registration and onboarding, wallet creation, PIN and OTP enrolment, recipient discovery (the app already knows the recipient's `userId`), deposits and withdrawals.
- Provisioned in advance with the initial data set: users, devices, PIN hashes, OTP secrets, wallets with their initial balances, and the recipients' contact data in Notification.
- OTP means a 6-digit time-based one-time password (TOTP, RFC 6238) generated by an authenticator app from the provisioned secret.

---

## 2. Domain schemas

Entities per bounded context, with fields, types and constraints. Each service owns its database (ADR 006).

```yaml
components:
  schemas:

    # ================= Identity (identity_db) =================
    User:
      description: >
        Password and PIN are stored only as argon2 hashes; the OTP secret is stored encrypted.
        Identity is the owner of the contact data (email, phone). status LOCKED is an
        administrative lock; the temporary login lockout (5 failures in 15 minutes) lives in Redis (§2.1).
      type: object
      required: [userId, email, status, roles, createdAt]
      properties:
        userId:    { type: string, format: uuid }
        email:     { type: string, format: email, description: "UNIQUE" }
        phone:     { type: string, nullable: true, description: "E.164" }
        status:    { type: string, enum: [ACTIVE, LOCKED, DISABLED] }
        roles:     { type: array, items: { type: string, enum: [USER, AUDITOR] } }
        createdAt: { type: string, format: date-time }

    Device:
      type: object
      required: [deviceId, userId, registeredAt]
      properties:
        deviceId:     { type: string, maxLength: 128 }
        userId:       { type: string, format: uuid }
        registeredAt: { type: string, format: date-time }
        lastSeenAt:   { type: string, format: date-time }

    RefreshToken:
      description: >
        Rotating, bound to one device, lifetime 7 days. All the refresh tokens issued from one
        login on one device form a family; the family is the user's session on that device and
        its familyId is the sid claim of every access token issued in it. Presenting a ROTATED
        token revokes the whole family (session).
      type: object
      required: [tokenId, familyId, userId, deviceId, status, expiresAt]
      properties:
        tokenId:   { type: string, format: uuid, description: "Only its SHA-256 is stored" }
        familyId:  { type: string, format: uuid, description: "Session id (sid)" }
        userId:    { type: string, format: uuid }
        deviceId:  { type: string }
        status:    { type: string, enum: [ACTIVE, ROTATED, REVOKED] }
        expiresAt: { type: string, format: date-time }

    AccessTokenClaims:
      description: "JWT signed with RS256 (keys published as JWKS), lifetime 15 minutes."
      type: object
      required: [iss, sub, did, sid, jti, roles, iat, exp]
      properties:
        iss:   { type: string, example: "finova-identity" }
        sub:   { type: string, format: uuid, description: "userId" }
        did:   { type: string, description: "deviceId" }
        sid:   { type: string, format: uuid, description: "Session id = RefreshToken.familyId" }
        jti:   { type: string, format: uuid }
        roles: { type: array, items: { type: string } }
        iat:   { type: integer }
        exp:   { type: integer }

    FinancialSession:
      description: >
        Issued by POST /auth/financial-session after a PIN or OTP challenge. Signed JWT,
        lifetime 5 minutes, scope transfer:create, bound to the user and device. Single use:
        it authorizes exactly one transfer intent (one Idempotency-Key); retries of that same
        intent are allowed (§3.1, step 3).
      type: object
      required: [sessionId, userId, deviceId, scope, method, issuedAt, expiresAt]
      properties:
        sessionId: { type: string, format: uuid, description: "jti of the token" }
        userId:    { type: string, format: uuid }
        deviceId:  { type: string }
        scope:     { type: string, enum: ["transfer:create"] }
        method:    { type: string, enum: [PIN, OTP] }
        issuedAt:  { type: string, format: date-time }
        expiresAt: { type: string, format: date-time }

    # ================= Wallet (wallet_db) =================
    Wallet:
      type: object
      required: [walletId, userId, balance, currency, version, updatedAt]
      properties:
        walletId:  { type: string, format: uuid }
        userId:    { type: string, format: uuid, description: "UNIQUE — one wallet per user" }
        balance:   { type: string, pattern: '^\d{1,17}\.\d{2}$', description: "NUMERIC(19,2), CHECK (balance >= 0)" }
        currency:  { type: string, enum: [COP] }
        version:   { type: integer, minimum: 0, description: "+1 on every debit, credit and reversal; propagated in BalanceChanged" }
        updatedAt: { type: string, format: date-time }

    LedgerEntry:
      description: "Append-only record of a financial effect. UNIQUE(transfer_id, type)."
      type: object
      required: [entryId, walletId, transferId, type, amount, balanceAfter, createdAt]
      properties:
        entryId:      { type: string, format: uuid }
        walletId:     { type: string, format: uuid }
        transferId:   { type: string, format: uuid }
        type:         { type: string, enum: [DEBIT, CREDIT, REVERSAL] }
        amount:       { type: string, pattern: '^\d{1,17}\.\d{2}$' }
        balanceAfter: { type: string, pattern: '^\d{1,17}\.\d{2}$' }
        createdAt:    { type: string, format: date-time }

    TransferSettlement:
      description: >
        Marker that settles a debited transfer exactly once (ADR 002). PRIMARY KEY (transfer_id).
        The credit writes CREDITED together with its CREDIT ledger entry, and the reversal writes
        REVERSED together with its REVERSAL ledger entry, each in its own local transaction.
        Both claim this row through its primary key, so the first one wins: when ReverseDebit
        finds CREDITED, the credit was already applied; when a late CreditWallet finds REVERSED,
        it is rejected. A transfer can never be both credited and reversed.
      type: object
      required: [transferId, outcome, settledAt]
      properties:
        transferId: { type: string, format: uuid }
        outcome:    { type: string, enum: [CREDITED, REVERSED] }
        settledAt:  { type: string, format: date-time }

    # ================= Transfer (transfer_db) =================
    Transfer:
      description: "UNIQUE(sender_user_id, idempotency_key)."
      type: object
      required: [transferId, senderUserId, recipientUserId, amount, currency, idempotencyKey, requestHash, status, creditAttempts, correlationId, lastCommandAt, createdAt, updatedAt]
      properties:
        transferId:      { type: string, format: uuid }
        senderUserId:    { type: string, format: uuid }
        recipientUserId: { type: string, format: uuid }
        amount:          { type: string, pattern: '^\d{1,17}\.\d{2}$' }
        currency:        { type: string, enum: [COP] }
        description:     { type: string, maxLength: 140, nullable: true }
        idempotencyKey:  { type: string, format: uuid }
        requestHash:     { type: string, pattern: '^[a-f0-9]{64}$', description: "SHA-256 of the canonical request body" }
        status:          { $ref: '#/components/schemas/TransferStatus' }
        failureReason:
          type: string
          nullable: true
          enum: [INSUFFICIENT_FUNDS, SENDER_WALLET_NOT_FOUND, RECIPIENT_WALLET_NOT_FOUND, CREDIT_TIMEOUT]
        creditAttempts:  { type: integer, minimum: 0, maximum: 3 }
        correlationId:   { type: string, format: uuid }
        lastCommandAt:   { type: string, format: date-time, description: "When the last saga command was sent; used by the timeout sweeper (§4.6)" }
        createdAt:       { type: string, format: date-time }
        updatedAt:       { type: string, format: date-time }
        completedAt:     { type: string, format: date-time, nullable: true }

    TransferStatus:
      type: string
      enum: [PENDING, DEBITED, COMPLETED, REJECTED, COMPENSATING, REVERSED]
      description: |
        PENDING      -> DEBITED       on WalletDebited
        PENDING      -> REJECTED      on DebitRejected                                         (terminal)
        DEBITED      -> COMPLETED     on WalletCredited                                        (terminal)
        DEBITED      -> COMPENSATING  on CreditFailed, or 3 CreditWallet attempts without reply (10 s each)
        COMPENSATING -> REVERSED      on DebitReversed                                         (terminal)
        COMPENSATING -> COMPLETED     on WalletCredited: Wallet found the credit already applied (terminal, only exception)

    TransferView:
      description: "Public representation returned by the API: Transfer without idempotencyKey, requestHash, creditAttempts, correlationId and lastCommandAt."
      type: object
      required: [transferId, senderUserId, recipientUserId, amount, currency, status, createdAt, updatedAt]
      properties:
        transferId:      { type: string, format: uuid }
        senderUserId:    { type: string, format: uuid }
        recipientUserId: { type: string, format: uuid }
        amount:          { type: string, pattern: '^\d{1,17}\.\d{2}$' }
        currency:        { type: string, enum: [COP] }
        description:     { type: string, nullable: true }
        status:          { $ref: '#/components/schemas/TransferStatus' }
        failureReason:   { type: string, nullable: true }
        createdAt:       { type: string, format: date-time }
        updatedAt:       { type: string, format: date-time }
        completedAt:     { type: string, format: date-time, nullable: true }

    # ================= Shared by Transfer and Wallet =================
    OutboxMessage:
      description: "Table outbox_messages (transfer_db and wallet_db), written in the same transaction as the state change."
      type: object
      required: [id, exchange, routingKey, payload, createdAt]
      properties:
        id:          { type: string, format: uuid, description: "= envelope messageId" }
        exchange:    { type: string }
        routingKey:  { type: string }
        payload:     { type: object, description: "JSONB with the full MessageEnvelope (§4.1)" }
        createdAt:   { type: string, format: date-time }
        publishedAt: { type: string, format: date-time, nullable: true, description: "Set to null again by the sweeper to republish the same message (§4.6)" }

    ProcessedMessage:
      description: >
        Table processed_messages (transfer_db and wallet_db). PRIMARY KEY (message_id, consumer).
        Written in the same transaction as the effect of the message. Wallet also stores the
        result it emitted, so a repeated command is answered with the original result (§4.7).
      type: object
      required: [messageId, consumer, processedAt]
      properties:
        messageId:     { type: string, format: uuid }
        consumer:      { type: string }
        processedAt:   { type: string, format: date-time }
        resultType:    { type: string, nullable: true, description: "Wallet only: WalletDebited, DebitRejected, WalletCredited, CreditFailed or DebitReversed" }
        resultPayload: { type: object, nullable: true, description: "Wallet only: payload of that result" }

    # ================= Audit (audit_db) =================
    AuditRecord:
      description: >
        Table audit_records, partitioned monthly. The service role has INSERT and SELECT only;
        a trigger rejects UPDATE, DELETE and TRUNCATE.
        hash = SHA-256(prevHash || canonicalJson(record without prevHash and hash)).
        Genesis prevHash = 64 zeros. Records are inserted by a single active consumer, so seq
        defines one total order.
      type: object
      required: [seq, eventId, source, stage, outcome, correlationId, occurredAt, recordedAt, prevHash, hash]
      properties:
        seq:            { type: integer, format: int64, description: "Monotonic sequence; defines the chain order" }
        eventId:        { type: string, format: uuid }
        source:         { type: string, enum: [GATEWAY, TRANSFER, WALLET] }
        stage:          { type: string, enum: [REQUEST, DEBIT, CREDIT, REVERSAL, TRANSFER] }
        outcome:        { type: string, enum: [ACCEPTED, REJECTED, DUPLICATE, FAILED, COMPENSATED, COMPLETED, APPLIED] }
        reason:         { type: string, nullable: true, description: "See §4.8" }
        transferId:     { type: string, format: uuid, nullable: true }
        userId:         { type: string, format: uuid, nullable: true, description: "null when the Gateway rejects an unauthenticated request" }
        idempotencyKey: { type: string, nullable: true }
        amount:         { type: string, nullable: true }
        httpStatus:     { type: integer, nullable: true }
        correlationId:  { type: string, format: uuid }
        occurredAt:     { type: string, format: date-time }
        recordedAt:     { type: string, format: date-time }
        prevHash:       { type: string, pattern: '^[a-f0-9]{64}$' }
        hash:           { type: string, pattern: '^[a-f0-9]{64}$' }

    AuditEventId:
      description: >
        Table audit_event_ids (not partitioned), PRIMARY KEY (event_id), inserted in the same
        transaction as the audit record. It enforces UNIQUE(eventId), because PostgreSQL requires
        the partition key in unique constraints of partitioned tables.
      type: object
      required: [eventId, seq]
      properties:
        eventId: { type: string, format: uuid }
        seq:     { type: integer, format: int64 }

    # ================= Notification (notification_db) =================
    RecipientContact:
      description: >
        Table recipient_contacts, PRIMARY KEY (user_id). Copy of the contact data owned by Identity,
        provisioned from Identity's user records in the initial data set. Notification never calls
        Identity at runtime.
      type: object
      required: [userId, email, preferredChannel]
      properties:
        userId:           { type: string, format: uuid }
        email:            { type: string, format: email }
        phone:            { type: string, nullable: true }
        pushToken:        { type: string, nullable: true }
        preferredChannel: { type: string, enum: [PUSH, EMAIL, SMS] }

    NotificationLog:
      description: >
        Table notification_log. UNIQUE(transfer_id, event_type, user_id): one row per notification.
        A retry updates the same row (status and attempts); a SENT row is never sent again.
      type: object
      required: [notificationId, transferId, eventType, userId, channel, status, attempts, createdAt, updatedAt]
      properties:
        notificationId: { type: string, format: uuid }
        transferId:     { type: string, format: uuid }
        eventType:      { type: string, enum: [TransferCompleted, TransferRejected, TransferReversed] }
        userId:         { type: string, format: uuid }
        channel:        { type: string, enum: [PUSH, EMAIL, SMS] }
        status:         { type: string, enum: [SENT, FAILED] }
        attempts:       { type: integer, minimum: 1 }
        createdAt:      { type: string, format: date-time }
        updatedAt:      { type: string, format: date-time }
```

### 2.1 Redis keys

| Key | Writer | Value | TTL |
|---|---|---|---|
| `idem:{userId}:{idempotencyKey}` | Gateway | `{state: IN_PROGRESS\|COMPLETED, requestHash, httpStatus?, body?}` | 86400 s |
| `revoked:jti:{jti}` | Identity | `1` | Remaining lifetime of the access token |
| `revoked:sid:{sid}` | Identity | `1` (session revoked: logout or refresh-token reuse) | 900 s (lifetime of the last access token issued in the session) |
| `fs:used:{jti}` | Gateway | Idempotency-Key that consumed the financial session | 300 s |
| `rl:{scope}:{subject}` | Gateway | Rate-limit counter | 60 s |
| `fail:login:{email}`, `lock:login:{email}` | Gateway | Failed-login counter / lock flag | 900 s |
| `fail:stepup:{userId}`, `lock:stepup:{userId}` | Gateway | Failed step-up counter / lock flag | 900 s |
| `balance:{userId}` | Wallet (projection consumer and cache-miss path) | Hash `{walletId, available, currency, version, asOf}` | None |

`balance:{userId}` is always written with a Lua compare-and-set: only if `incoming.version > stored.version` or the key does not exist. Both the projection consumer and the cache-miss path use it, so a late write never overwrites a newer balance.

---

## 3. REST APIs

### 3.1 Gateway processing pipeline

Every request goes through the API Gateway, which applies these steps in order. Steps 3 and 4 apply only to `POST /transfers`.

| Step | Action | Failure |
|---|---|---|
| 1 | **Authentication** (routes with Bearer): validate the JWT locally with Identity's JWKS (RS256 signature, `exp`, `iss`) and check `revoked:jti:{jti}` and `revoked:sid:{sid}` in Redis. On `/audit/*`, require the role `AUDITOR`. | `401 UNAUTHORIZED` / `401 TOKEN_REVOKED` / `403 FORBIDDEN_ROLE` |
| 2 | **Rate limiting and lockout** with counters in Redis (§3.1.2). | `429 RATE_LIMIT_EXCEEDED` / `423 ACCOUNT_LOCKED` (login) / `423 STEP_UP_LOCKED` (step-up) |
| 3 | **Financial session:** valid signature and `exp`, `scope = transfer:create`, `sub` and `did` equal to those of the access token. Single use per transfer intent: `SET fs:used:{jti} {idempotencyKey} NX EX 300`. If it was already used **with the same** Idempotency-Key, continue (retry of the same intent, decided by step 4). If it was used **with another** key, reject. | `403 FINANCIAL_SESSION_REQUIRED` / `_INVALID` / `_EXPIRED` / `_USED` |
| 4 | **Idempotency pre-check** (§3.1.1). | `400`, `409`, `422`, `503` |
| 5 | **Route:** strip client identity headers, inject `X-Correlation-Id`, `X-User-Id`, `X-Device-Id`, `X-User-Roles`, and forward. | `502` / `504 UPSTREAM_UNAVAILABLE` |
| 6 | **Audit:** every `POST /transfers` rejected in steps 1–4, answered as a duplicate, or failed in step 5 (Transfer answered 5xx or did not answer) publishes `AttemptRecorded` (source `GATEWAY`) to `audit.events` (§4.8). | — |

**Redis unavailable.** Steps 1–4 depend on Redis. If Redis is unavailable, the Gateway fails closed: `POST /transfers` answers `503 IDEMPOTENCY_STORE_UNAVAILABLE` (ADR 003) and every other route answers `503 SECURITY_STORE_UNAVAILABLE`, because token revocation, rate limits and lockout cannot be verified. No request is routed without these checks.

#### 3.1.1 Idempotency pre-check (ADR 003)

- `Idempotency-Key` (UUID) is mandatory on `POST /transfers`.
- `requestHash = SHA-256(canonicalJson(body))`.
- Registration: `SET idem:{userId}:{idempotencyKey} {"state":"IN_PROGRESS","requestHash":"..."} NX EX 86400`.

The checks are evaluated in this order:

| Situation | Result | Audit (`outcome` / `reason`) |
|---|---|---|
| Key missing or not a UUID | `400 IDEMPOTENCY_KEY_REQUIRED`; not routed | `REJECTED` / `MISSING_KEY` |
| Key exists with a different hash (whatever the state) | `422 IDEMPOTENCY_KEY_REUSED` | `REJECTED` / `KEY_REUSED` |
| Key exists, same hash, `IN_PROGRESS` | `409 IDEMPOTENCY_IN_PROGRESS` | `DUPLICATE` / `IN_PROGRESS_CONFLICT` |
| Key exists, same hash, `COMPLETED` | Replay the stored status and body with header `Idempotent-Replayed: true` | `DUPLICATE` / `DUPLICATE_REPLAYED` |
| Key created | Route the request. When Transfer answers 2xx or 4xx, store `{state: COMPLETED, requestHash, httpStatus, body}` (same TTL). When Transfer answers 5xx or does not answer, delete the key so the client can retry with the same key. | Transfer records `ACCEPTED`; on 5xx or no answer the Gateway records `FAILED` (§4.8) |
| Redis unavailable | `503 IDEMPOTENCY_STORE_UNAVAILABLE`; not routed (fail closed) | `REJECTED` / `IDEMPOTENCY_STORE_UNAVAILABLE` |

**Defense in depth in Transfer** (Redis lost the record, the key expired after 24 h, or the Gateway deleted it after a 5xx or timeout): `UNIQUE(sender_user_id, idempotency_key)` in `transfer_db`. If a request with an existing key reaches Transfer:

- same `requestHash` → Transfer answers `202` with the **existing** transfer (same `transferId`) and `Idempotent-Replayed: true`; no new transfer is created (audit `DUPLICATE` / `DUPLICATE_TRANSFER`);
- different `requestHash` → `422 IDEMPOTENCY_KEY_REUSED`.

Wallet operations are also idempotent per `transferId` (§4.7).

#### 3.1.2 Rate limiting and lockout

| Scope | Limit | Response |
|---|---|---|
| `POST /auth/login`, `POST /auth/refresh` | 10 req/min per client IP | `429 RATE_LIMIT_EXCEEDED` |
| Failed logins | 5 per account in 15 min → login locked for 15 min | `423 ACCOUNT_LOCKED` |
| Failed financial-session challenges | 5 per user in 15 min → step-up locked for 15 min | `423 STEP_UP_LOCKED` |
| `POST /auth/financial-session` | 10 req/min per user | `429 RATE_LIMIT_EXCEEDED` |
| `POST /transfers` | 30 req/min per user | `429 RATE_LIMIT_EXCEEDED` |
| `GET /wallets/me/balance` | 120 req/min per user | `429 RATE_LIMIT_EXCEEDED` |
| `GET /transfers/{transferId}` | 120 req/min per user (status polling) | `429 RATE_LIMIT_EXCEEDED` |

`429` responses include `Retry-After`.

**Lockout counting.** The Gateway reads Identity's answer. On `401 INVALID_CREDENTIALS` from `POST /auth/login` it increments `fail:login:{email}`; on `200` it deletes the counter. On `401 STEP_UP_FAILED` from `POST /auth/financial-session` it increments `fail:stepup:{userId}`; on `201` it deletes the counter. When a counter reaches 5, the Gateway sets the matching `lock:*` key, and while it exists that route answers `423` without reaching Identity.

#### 3.1.3 Routes

| Method | Path | Upstream | Authentication |
|---|---|---|---|
| POST | `/auth/login` | Identity | None |
| POST | `/auth/refresh` | Identity | None |
| POST | `/auth/logout` | Identity | Bearer |
| POST | `/auth/financial-session` | Identity | Bearer |
| GET | `/wallets/me/balance` | Wallet | Bearer |
| POST | `/transfers` | Transfer | Bearer + `X-Financial-Session` + `Idempotency-Key` |
| GET | `/transfers/{transferId}` | Transfer | Bearer |
| GET | `/audit/records` | Audit | Bearer, role `AUDITOR` |
| GET | `/audit/verify` | Audit | Bearer, role `AUDITOR` |

**Internal endpoint (not routed by the Gateway):** `GET /.well-known/jwks.json` on Identity. Authentication: none, reachable only inside the Docker network. Request body: none. Responses: `200` with the JSON Web Key Set (`{"keys":[{"kty":"RSA","kid":"2026-10","use":"sig","alg":"RS256","n":"...","e":"AQAB"}]}`); `500 INTERNAL_ERROR`. The Gateway caches the keys and refreshes them when it sees an unknown `kid`.

### 3.2 Error model

Every error response uses `ErrorResponse`:

```json
{
  "code": "IDEMPOTENCY_KEY_REUSED",
  "message": "Idempotency-Key was already used with a different request body.",
  "correlationId": "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11",
  "timestamp": "2026-10-19T15:00:00Z",
  "details": {}
}
```

| Code | HTTP | Origin | Description |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | Any | Invalid body or parameters |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Gateway | Missing or invalid `Idempotency-Key` |
| `SELF_TRANSFER_NOT_ALLOWED` | 400 | Transfer | Recipient equals sender |
| `UNAUTHORIZED` | 401 | Gateway | Missing, invalid or expired access token |
| `TOKEN_REVOKED` | 401 | Gateway | `jti` or session (`sid`) revoked |
| `INVALID_CREDENTIALS` | 401 | Identity | Wrong email or password |
| `REFRESH_TOKEN_INVALID` | 401 | Identity | Unknown, expired, revoked or device mismatch |
| `REFRESH_TOKEN_REUSED` | 401 | Identity | Rotated token reused; session revoked |
| `STEP_UP_FAILED` | 401 | Identity | Wrong PIN or OTP |
| `FINANCIAL_SESSION_REQUIRED` | 403 | Gateway | Missing `X-Financial-Session` |
| `FINANCIAL_SESSION_INVALID` | 403 | Gateway | Bad signature, scope, user or device |
| `FINANCIAL_SESSION_EXPIRED` | 403 | Gateway | Older than 5 minutes |
| `FINANCIAL_SESSION_USED` | 403 | Gateway | Already consumed by another transfer intent |
| `FORBIDDEN_ROLE` | 403 | Gateway | Role `AUDITOR` required |
| `WALLET_NOT_FOUND` | 404 | Wallet | User has no wallet |
| `TRANSFER_NOT_FOUND` | 404 | Transfer | Not found or not visible to the caller |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | Gateway | Same key still in progress |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Gateway / Transfer | Same key, different body |
| `ACCOUNT_LOCKED` | 423 | Gateway | Login lockout active |
| `STEP_UP_LOCKED` | 423 | Gateway | Step-up lockout active |
| `RATE_LIMIT_EXCEEDED` | 429 | Gateway | Rate limit exceeded |
| `INTERNAL_ERROR` | 500 | Any | Unexpected error |
| `UPSTREAM_UNAVAILABLE` | 502 / 504 | Gateway | Upstream service down or timed out |
| `IDEMPOTENCY_STORE_UNAVAILABLE` | 503 | Gateway | Redis down; transfers fail closed |
| `SECURITY_STORE_UNAVAILABLE` | 503 | Gateway | Redis down; other routes fail closed |

Asynchronous outcomes are not HTTP errors. They appear in `Transfer.failureReason`: `INSUFFICIENT_FUNDS`, `SENDER_WALLET_NOT_FOUND`, `RECIPIENT_WALLET_NOT_FOUND`, `CREDIT_TIMEOUT`.

### 3.3 `POST /auth/login`

- **Authentication:** none (IP rate limit and login lockout apply).
- **Request body:** `email` (string, email, required), `password` (string, required), `deviceId` (string ≤ 128, required).
- **Behavior:** Identity verifies the argon2 hash against `identity_db`, opens a session (new refresh-token family) on the device and issues a token pair.
- **Responses:**

  | Status | Code / meaning |
  |---|---|
  | `200` | Token pair issued (`accessToken`, `tokenType`, `expiresIn`, `refreshToken`, `refreshExpiresIn`) |
  | `400` | `VALIDATION_ERROR` |
  | `401` | `INVALID_CREDENTIALS` (counts toward the lockout) |
  | `423` | `ACCOUNT_LOCKED` |
  | `429` | `RATE_LIMIT_EXCEEDED` |
  | `500` | `INTERNAL_ERROR` |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` |
  | `503` | `SECURITY_STORE_UNAVAILABLE` |

- **Example:**

```http
POST /auth/login
Content-Type: application/json

{ "email": "ana@example.com", "password": "********", "deviceId": "ios-7F3A9C" }
```
```json
200 OK
{
  "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "rt_9b1c...",
  "refreshExpiresIn": 604800
}
```

### 3.4 `POST /auth/refresh`

- **Authentication:** none (IP rate limit applies).
- **Request body:** `refreshToken` (string, required), `deviceId` (string, required).
- **Behavior:** rotates the token; the presented one becomes `ROTATED` and the new one stays in the same family (session). Presenting a `ROTATED` token revokes the whole family and writes `revoked:sid:{sid}`, so every access token of that session is rejected at once and the user must log in again.
- **Responses:**

  | Status | Code / meaning |
  |---|---|
  | `200` | New token pair, same shape as login |
  | `400` | `VALIDATION_ERROR` |
  | `401` | `REFRESH_TOKEN_INVALID` (unknown, expired, revoked or device mismatch) |
  | `401` | `REFRESH_TOKEN_REUSED` (session revoked) |
  | `429` | `RATE_LIMIT_EXCEEDED` |
  | `500` | `INTERNAL_ERROR` |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` |
  | `503` | `SECURITY_STORE_UNAVAILABLE` |

- **Example:**

```http
POST /auth/refresh
Content-Type: application/json

{ "refreshToken": "rt_9b1c...", "deviceId": "ios-7F3A9C" }
```
```json
200 OK
{
  "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "rt_4f7e...",
  "refreshExpiresIn": 604800
}
```

### 3.5 `POST /auth/logout`

- **Authentication:** Bearer.
- **Request body:** none.
- **Behavior:** revokes the session of the device: the refresh-token family becomes `REVOKED` and Identity writes `revoked:sid:{sid}` and `revoked:jti:{jti}`.
- **Responses:**

  | Status | Code / meaning |
  |---|---|
  | `204` | Logged out |
  | `401` | `UNAUTHORIZED` / `TOKEN_REVOKED` |
  | `500` | `INTERNAL_ERROR` |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` |
  | `503` | `SECURITY_STORE_UNAVAILABLE` |

- **Example:**

```http
POST /auth/logout
Authorization: Bearer eyJhbGciOi...
```
```http
204 No Content
```

### 3.6 `POST /auth/financial-session` (step-up)

- **Authentication:** Bearer (step-up lockout applies).
- **Request body:** `method` (`PIN` | `OTP`, required), `credential` (string, required: the PIN, or the 6-digit TOTP code), `scope` (`transfer:create`, required).
- **Behavior:** Identity verifies the PIN hash or the TOTP code and issues a financial session token: 5 minutes, single use (one transfer intent), scope `transfer:create`, bound to the user and device of the access token.
- **Responses:**

  | Status | Code / meaning |
  |---|---|
  | `201` | Financial session issued (`financialSessionToken`, `scope`, `expiresIn`) |
  | `400` | `VALIDATION_ERROR` |
  | `401` | `UNAUTHORIZED` / `TOKEN_REVOKED` |
  | `401` | `STEP_UP_FAILED` (counts toward the step-up lockout) |
  | `423` | `STEP_UP_LOCKED` |
  | `429` | `RATE_LIMIT_EXCEEDED` |
  | `500` | `INTERNAL_ERROR` |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` |
  | `503` | `SECURITY_STORE_UNAVAILABLE` |

- **Example:**

```http
POST /auth/financial-session
Authorization: Bearer eyJhbGciOi...
Content-Type: application/json

{ "method": "PIN", "credential": "4821", "scope": "transfer:create" }
```
```json
201 Created
{ "financialSessionToken": "eyJhbGciOiJSUzI1NiJ9...", "scope": "transfer:create", "expiresIn": 300 }
```

### 3.7 `GET /wallets/me/balance`

- **Authentication:** Bearer.
- **Request body:** none. The user is resolved from `X-User-Id` (token `sub`).
- **Behavior:** Wallet reads `balance:{userId}` from Redis and answers without querying PostgreSQL. On a cache miss, or if Wallet's own Redis read fails or times out, it reads `wallet_db`, writes the value to Redis with its `version` using the compare-and-set (§2.1) when Redis is reachable, and answers. The value is eventually consistent by milliseconds; it is never used to authorize a debit.
- **Responses:**

  | Status | Code / meaning |
  |---|---|
  | `200` | Balance (`walletId`, `available`, `currency`, `version`, `asOf`) |
  | `401` | `UNAUTHORIZED` / `TOKEN_REVOKED` |
  | `404` | `WALLET_NOT_FOUND` |
  | `429` | `RATE_LIMIT_EXCEEDED` |
  | `500` | `INTERNAL_ERROR` |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` |
  | `503` | `SECURITY_STORE_UNAVAILABLE` |

- **Example:**

```http
GET /wallets/me/balance
Authorization: Bearer eyJhbGciOi...
```
```json
200 OK
{
  "walletId": "0c7a0d2e-3f0b-4b8e-9a7c-2d1e5f6a7b8c",
  "available": "850000.00",
  "currency": "COP",
  "version": 42,
  "asOf": "2026-10-19T15:00:00.120Z"
}
```

### 3.8 `POST /transfers`

- **Authentication:** Bearer + `X-Financial-Session` + `Idempotency-Key` (pipeline §3.1).
- **Request body:**

  | Field | Type | Required | Rule |
  |---|---|---|---|
  | `recipientUserId` | uuid | Yes | ≠ sender (checked synchronously). That the recipient has a wallet is checked asynchronously by Wallet during the saga (§4.7). |
  | `amount` | decimal string | Yes | > 0, 2 decimals |
  | `currency` | `COP` | Yes | |
  | `description` | string ≤ 140 | No | |

- **Behavior:** in one `transfer_db` transaction, Transfer stores the transfer as `PENDING` and writes `DebitWallet` and `AttemptRecorded(ACCEPTED)` to its outbox, then answers `202 Accepted`. Funds and the recipient's wallet are checked asynchronously by the saga (§4.6); the client follows the status at `GET /transfers/{transferId}`. A request whose key already exists in `transfer_db` is answered as described in §3.1.1 (defense in depth).
- **Responses:**

  | Status | Code | Origin |
  |---|---|---|
  | `202` | — | Transfer. Body `{transferId, status, statusUrl, createdAt}`; headers `Location`, `X-Correlation-Id`; `Idempotent-Replayed: true` on a replay. |
  | `400` | `IDEMPOTENCY_KEY_REQUIRED` | Gateway |
  | `400` | `VALIDATION_ERROR`, `SELF_TRANSFER_NOT_ALLOWED` | Transfer |
  | `401` | `UNAUTHORIZED`, `TOKEN_REVOKED` | Gateway |
  | `403` | `FINANCIAL_SESSION_REQUIRED`, `FINANCIAL_SESSION_INVALID`, `FINANCIAL_SESSION_EXPIRED`, `FINANCIAL_SESSION_USED` | Gateway |
  | `409` | `IDEMPOTENCY_IN_PROGRESS` | Gateway |
  | `422` | `IDEMPOTENCY_KEY_REUSED` | Gateway / Transfer |
  | `429` | `RATE_LIMIT_EXCEEDED` | Gateway |
  | `500` | `INTERNAL_ERROR` | Any |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` | Gateway |
  | `503` | `IDEMPOTENCY_STORE_UNAVAILABLE` | Gateway (Redis down, fail closed) |

- **Example:**

```http
POST /transfers
Authorization: Bearer eyJhbGciOi...
X-Financial-Session: eyJhbGciOi...
Idempotency-Key: 7e57d004-2b97-4e7a-b45f-5387367791cd
Content-Type: application/json

{ "recipientUserId": "b3c9a1e2-5d4f-4a6b-8c7d-9e0f1a2b3c4d", "amount": "150000.00", "currency": "COP", "description": "Dinner" }
```
```json
202 Accepted
Location: /transfers/a1b2c3d4-e5f6-7890-abcd-ef1234567890

{
  "transferId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "PENDING",
  "statusUrl": "/transfers/a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "createdAt": "2026-10-19T15:00:00Z"
}
```

### 3.9 `GET /transfers/{transferId}`

- **Authentication:** Bearer. Visible only to the sender and the recipient; anyone else gets `404`.
- **Request body:** none.
- **Responses:**

  | Status | Code / meaning |
  |---|---|
  | `200` | `TransferView` (§2) |
  | `401` | `UNAUTHORIZED` / `TOKEN_REVOKED` |
  | `404` | `TRANSFER_NOT_FOUND` |
  | `429` | `RATE_LIMIT_EXCEEDED` |
  | `500` | `INTERNAL_ERROR` |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` |
  | `503` | `SECURITY_STORE_UNAVAILABLE` |

- **Example:**

```http
GET /transfers/a1b2c3d4-e5f6-7890-abcd-ef1234567890
Authorization: Bearer eyJhbGciOi...
```
```json
200 OK
{
  "transferId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "senderUserId": "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b",
  "recipientUserId": "b3c9a1e2-5d4f-4a6b-8c7d-9e0f1a2b3c4d",
  "amount": "150000.00",
  "currency": "COP",
  "description": "Dinner",
  "status": "REVERSED",
  "failureReason": "CREDIT_TIMEOUT",
  "createdAt": "2026-10-19T15:00:00Z",
  "updatedAt": "2026-10-19T15:00:31Z",
  "completedAt": null
}
```

### 3.10 `GET /audit/records`

- **Authentication:** Bearer, role `AUDITOR` (checked by the Gateway and again by Audit).
- **Request body:** none. **Query:** `transferId`, `userId`, `from`, `to` (date-time), `page` (default 0), `size` (default 50, max 200). At least one of `transferId`, `userId`, `from` is required.
- **Behavior:** read-only query on `audit_db`; results ordered by `seq` ascending (chronological).
- **Responses:**

  | Status | Code / meaning |
  |---|---|
  | `200` | Page of `AuditRecord` (`page`, `size`, `total`, `items`) |
  | `400` | `VALIDATION_ERROR` |
  | `401` | `UNAUTHORIZED` / `TOKEN_REVOKED` |
  | `403` | `FORBIDDEN_ROLE` |
  | `500` | `INTERNAL_ERROR` |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` |
  | `503` | `SECURITY_STORE_UNAVAILABLE` |

- **Example** (first two of the records of a completed transfer):

```http
GET /audit/records?transferId=a1b2c3d4-e5f6-7890-abcd-ef1234567890&size=2
Authorization: Bearer eyJhbGciOi...
```
```json
200 OK
{
  "page": 0, "size": 2, "total": 4,
  "items": [
    {
      "seq": 10231,
      "eventId": "3f9a6c1e-7b2d-4c5e-8f9a-0b1c2d3e4f5a",
      "source": "TRANSFER",
      "stage": "REQUEST",
      "outcome": "ACCEPTED",
      "reason": null,
      "transferId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "userId": "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b",
      "idempotencyKey": "7e57d004-2b97-4e7a-b45f-5387367791cd",
      "amount": "150000.00",
      "httpStatus": 202,
      "correlationId": "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11",
      "occurredAt": "2026-10-19T15:00:00Z",
      "recordedAt": "2026-10-19T15:00:00.040Z",
      "prevHash": "9c1e...",
      "hash": "4ab7..."
    },
    {
      "seq": 10232,
      "eventId": "8b2d4f6a-1c3e-4a5b-9d7f-2e4a6c8b0d1f",
      "source": "WALLET",
      "stage": "DEBIT",
      "outcome": "APPLIED",
      "reason": null,
      "transferId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "userId": "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b",
      "idempotencyKey": null,
      "amount": "150000.00",
      "httpStatus": null,
      "correlationId": "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11",
      "occurredAt": "2026-10-19T15:00:00.210Z",
      "recordedAt": "2026-10-19T15:00:00.260Z",
      "prevHash": "4ab7...",
      "hash": "e03c..."
    }
  ]
}
```

### 3.11 `GET /audit/verify`

- **Authentication:** Bearer, role `AUDITOR`.
- **Request body:** none. **Query:** `fromSeq` (default: genesis), `toSeq` (default: last record).
- **Behavior:** recomputes `SHA-256(prevHash || canonicalJson(record without prevHash and hash))` over the range and checks every link.
- **Responses:**

  | Status | Code / meaning |
  |---|---|
  | `200` | Verification result (`valid`, `checkedRecords`, `firstInvalidSeq`, `verifiedAt`) |
  | `400` | `VALIDATION_ERROR` |
  | `401` | `UNAUTHORIZED` / `TOKEN_REVOKED` |
  | `403` | `FORBIDDEN_ROLE` |
  | `500` | `INTERNAL_ERROR` |
  | `502` / `504` | `UPSTREAM_UNAVAILABLE` |
  | `503` | `SECURITY_STORE_UNAVAILABLE` |

- **Example:**

```http
GET /audit/verify
Authorization: Bearer eyJhbGciOi...
```
```json
200 OK
{ "valid": false, "checkedRecords": 10231, "firstInvalidSeq": 8812, "verifiedAt": "2026-10-19T16:00:00Z" }
```

---

## 4. Asynchronous events (RabbitMQ)

### 4.1 Envelope (every message)

```json
{
  "messageId": "uuid",
  "type": "DebitWallet",
  "correlationId": "uuid",
  "transferId": "uuid | null",
  "producer": "transfer-service",
  "occurredAt": "2026-10-19T15:00:00Z",
  "payload": { }
}
```

`messageId` is also the AMQP `message-id` property. Consumers deduplicate on it (`processed_messages`, or the table named in §4.3).

### 4.2 Topology

| Exchange (type) | Routing keys | Queue → Consumer | Retry queue | DLQ |
|---|---|---|---|---|
| `wallet.commands` (direct) | `debit`, `credit`, `reverse` | `wallet.commands.q` → Wallet | `wallet.commands.q.retry` | `wallet.commands.q.dlq` |
| `wallet.events` (topic) | `wallet.debited`, `wallet.debit-rejected`, `wallet.credited`, `wallet.credit-failed`, `wallet.debit-reversed` | `transfer.wallet-events.q` → Transfer | `transfer.wallet-events.q.retry` | `transfer.wallet-events.q.dlq` |
| `wallet.events` (topic) | `wallet.balance-changed` | `wallet.balance-projection.q` → Wallet (balance projection) | `wallet.balance-projection.q.retry` | `wallet.balance-projection.q.dlq` |
| `transfer.events` (topic) | `transfer.completed`, `transfer.rejected`, `transfer.reversed` | `notification.transfer-events.q` → Notification | `notification.transfer-events.q.retry` | `notification.transfer-events.q.dlq` |
| `audit.events` (topic) | `audit.attempt` | `audit.attempts.q` → Audit (single active consumer) | `audit.attempts.q.retry` | `audit.attempts.q.dlq` |

All queues are durable quorum queues with manual acks; producers use publisher confirms.

**Retries with backoff.** On a technical error the consumer rejects the message without requeue. The queue dead-letters it to its `.retry` queue (message TTL 5 s), which dead-letters it back to the original queue. The consumer reads the `x-death` count; when it reaches the retry limit of the queue (§4.5), it publishes the message to the `.dlq` queue and acks it. Every message in a DLQ raises an alert.

### 4.3 Event catalog

One row per message: name, producer, consumers, exchange and routing key → queue, payload schema (§4.4) and delivery guarantees.

| Message | Kind | Producer | Consumer(s) | Exchange / routing key → queue | Payload (§4.4) | Ordering | Idempotency | Retries |
|---|---|---|---|---|---|---|---|---|
| `DebitWallet` | Command | Transfer (outbox) | Wallet | `wallet.commands` / `debit` → `wallet.commands.q` | `DebitWallet` | Not required | `processed_messages` (stored result) + `UNIQUE(transfer_id, type)` in the ledger | 3 + DLQ; saga resends (§4.6) |
| `CreditWallet` | Command | Transfer (outbox) | Wallet | `wallet.commands` / `credit` → `wallet.commands.q` | `CreditWallet` | Not required | `processed_messages` + `transfer_settlements` | 3 + DLQ; saga resends up to 3 attempts |
| `ReverseDebit` | Command | Transfer (outbox) | Wallet | `wallet.commands` / `reverse` → `wallet.commands.q` | `ReverseDebit` | Not required | `processed_messages` + `transfer_settlements` | 3 + DLQ; saga resends until settled |
| `WalletDebited` | Event | Wallet (outbox) | Transfer | `wallet.events` / `wallet.debited` → `transfer.wallet-events.q` | `WalletDebited` | Not required (state machine) | `processed_messages` | 3 + DLQ |
| `DebitRejected` | Event | Wallet (outbox) | Transfer | `wallet.events` / `wallet.debit-rejected` → `transfer.wallet-events.q` | `DebitRejected` | Not required (state machine) | `processed_messages` | 3 + DLQ |
| `WalletCredited` | Event | Wallet (outbox) | Transfer | `wallet.events` / `wallet.credited` → `transfer.wallet-events.q` | `WalletCredited` | Not required (state machine) | `processed_messages` | 3 + DLQ |
| `CreditFailed` | Event | Wallet (outbox) | Transfer | `wallet.events` / `wallet.credit-failed` → `transfer.wallet-events.q` | `CreditFailed` | Not required (state machine) | `processed_messages` | 3 + DLQ |
| `DebitReversed` | Event | Wallet (outbox) | Transfer | `wallet.events` / `wallet.debit-reversed` → `transfer.wallet-events.q` | `DebitReversed` | Not required (state machine) | `processed_messages` | 3 + DLQ |
| `BalanceChanged` | Event | Wallet (outbox) | Wallet balance projection | `wallet.events` / `wallet.balance-changed` → `wallet.balance-projection.q` | `BalanceChanged` | Not required (version compare-and-set) | Version check | 3 + DLQ; rebuild job |
| `TransferCompleted` | Event | Transfer (outbox) | Notification | `transfer.events` / `transfer.completed` → `notification.transfer-events.q` | `TransferCompleted` | Not required | `UNIQUE(transfer_id, event_type, user_id)` in `notification_log` | 3 + DLQ |
| `TransferRejected` | Event | Transfer (outbox) | Notification | `transfer.events` / `transfer.rejected` → `notification.transfer-events.q` | `TransferRejected` | Not required | `UNIQUE(transfer_id, event_type, user_id)` in `notification_log` | 3 + DLQ |
| `TransferReversed` | Event | Transfer (outbox) | Notification | `transfer.events` / `transfer.reversed` → `notification.transfer-events.q` | `TransferReversed` | Not required | `UNIQUE(transfer_id, event_type, user_id)` in `notification_log` | 3 + DLQ |
| `AttemptRecorded` | Event | Gateway (direct publish + local retry), Transfer (outbox), Wallet (outbox) | Audit | `audit.events` / `audit.attempt` → `audit.attempts.q` | `AttemptRecorded` | Total order (single active consumer) | `audit_event_ids` (PRIMARY KEY `event_id`) | 5 + DLQ |

### 4.4 Payload schemas (AsyncAPI)

Each message schema below describes the `payload` field of the envelope (§4.1). Operations are written from the consumers' point of view: `subscribe` lists the messages a consumer receives from the channel.

```yaml
asyncapi: 2.6.0
id: 'urn:finova:messaging'
info:
  title: Finova messaging contracts
  version: 3.2.0
  description: Message contracts between the Finova services over RabbitMQ (TS-004, ADR 002).
defaultContentType: application/json

servers:
  rabbitmq:
    url: rabbitmq:5672
    protocol: amqp
    protocolVersion: 0.9.1

channels:
  wallet.commands:
    description: Saga commands sent by Transfer; queue wallet.commands.q (consumer Wallet)
    bindings:
      amqp:
        is: routingKey
        exchange: { name: wallet.commands, type: direct, durable: true, autoDelete: false, vhost: / }
        bindingVersion: 0.2.0
    subscribe:
      operationId: consumeWalletCommands
      summary: Wallet consumes the saga commands sent by Transfer
      message:
        oneOf:
          - $ref: '#/components/messages/DebitWallet'
          - $ref: '#/components/messages/CreditWallet'
          - $ref: '#/components/messages/ReverseDebit'
  wallet.events:
    description: Wallet results (queue transfer.wallet-events.q, consumer Transfer) and BalanceChanged (queue wallet.balance-projection.q, consumer Wallet projection)
    bindings:
      amqp:
        is: routingKey
        exchange: { name: wallet.events, type: topic, durable: true, autoDelete: false, vhost: / }
        bindingVersion: 0.2.0
    subscribe:
      operationId: consumeWalletEvents
      summary: Transfer consumes wallet results; the Wallet balance projection consumes BalanceChanged
      message:
        oneOf:
          - $ref: '#/components/messages/WalletDebited'
          - $ref: '#/components/messages/DebitRejected'
          - $ref: '#/components/messages/WalletCredited'
          - $ref: '#/components/messages/CreditFailed'
          - $ref: '#/components/messages/DebitReversed'
          - $ref: '#/components/messages/BalanceChanged'
  transfer.events:
    description: Transfer outcomes; queue notification.transfer-events.q (consumer Notification)
    bindings:
      amqp:
        is: routingKey
        exchange: { name: transfer.events, type: topic, durable: true, autoDelete: false, vhost: / }
        bindingVersion: 0.2.0
    subscribe:
      operationId: consumeTransferEvents
      summary: Notification consumes transfer outcomes
      message:
        oneOf:
          - $ref: '#/components/messages/TransferCompleted'
          - $ref: '#/components/messages/TransferRejected'
          - $ref: '#/components/messages/TransferReversed'
  audit.events:
    description: Every transfer attempt and outcome; queue audit.attempts.q (consumer Audit, single active consumer)
    bindings:
      amqp:
        is: routingKey
        exchange: { name: audit.events, type: topic, durable: true, autoDelete: false, vhost: / }
        bindingVersion: 0.2.0
    subscribe:
      operationId: consumeAuditEvents
      summary: Audit consumes every attempt and outcome
      message:
        $ref: '#/components/messages/AttemptRecorded'

components:
  schemas:
    Money:   { type: string, pattern: '^\d{1,17}\.\d{2}$' }
    UserRef: { type: string, format: uuid }

  messages:
    # ---- Commands: Transfer -> Wallet (Wallet resolves the wallet by userId) ----
    DebitWallet:
      messageId: DebitWallet
      name: DebitWallet
      payload:
        type: object
        required: [transferId, userId, amount, currency]
        properties:
          transferId: { type: string, format: uuid }
          userId:     { $ref: '#/components/schemas/UserRef', description: "sender" }
          amount:     { $ref: '#/components/schemas/Money' }
          currency:   { type: string, enum: [COP] }
    CreditWallet:
      messageId: CreditWallet
      name: CreditWallet
      payload:
        type: object
        required: [transferId, userId, amount, currency, attempt]
        properties:
          transferId: { type: string, format: uuid }
          userId:     { $ref: '#/components/schemas/UserRef', description: "recipient" }
          amount:     { $ref: '#/components/schemas/Money' }
          currency:   { type: string, enum: [COP] }
          attempt:    { type: integer, minimum: 1, maximum: 3 }
    ReverseDebit:
      messageId: ReverseDebit
      name: ReverseDebit
      payload:
        type: object
        required: [transferId, userId, amount, currency, reason]
        properties:
          transferId: { type: string, format: uuid }
          userId:     { $ref: '#/components/schemas/UserRef', description: "sender" }
          amount:     { $ref: '#/components/schemas/Money' }
          currency:   { type: string, enum: [COP] }
          reason:     { type: string, enum: [RECIPIENT_WALLET_NOT_FOUND, CREDIT_TIMEOUT] }

    # ---- Results: Wallet -> Transfer ----
    WalletDebited:
      messageId: WalletDebited
      name: WalletDebited
      payload:
        type: object
        required: [transferId, walletId, userId, amount, version]
        properties:
          transferId: { type: string, format: uuid }
          walletId:   { type: string, format: uuid }
          userId:     { $ref: '#/components/schemas/UserRef' }
          amount:     { $ref: '#/components/schemas/Money' }
          version:    { type: integer }
    DebitRejected:
      messageId: DebitRejected
      name: DebitRejected
      payload:
        type: object
        required: [transferId, userId, reason]
        properties:
          transferId: { type: string, format: uuid }
          userId:     { $ref: '#/components/schemas/UserRef' }
          reason:     { type: string, enum: [INSUFFICIENT_FUNDS, SENDER_WALLET_NOT_FOUND] }
    WalletCredited:
      messageId: WalletCredited
      name: WalletCredited
      description: "Also re-emitted when a ReverseDebit finds that the credit had already been applied."
      payload:
        type: object
        required: [transferId, walletId, userId, amount, version]
        properties:
          transferId: { type: string, format: uuid }
          walletId:   { type: string, format: uuid }
          userId:     { $ref: '#/components/schemas/UserRef' }
          amount:     { $ref: '#/components/schemas/Money' }
          version:    { type: integer }
    CreditFailed:
      messageId: CreditFailed
      name: CreditFailed
      description: >
        Business failure only. Technical errors are not reported with this event: the message
        is retried (§4.2), and the saga's 10-second timeout applies.
        TRANSFER_REVERSED means a late CreditWallet arrived after the reversal; nothing was credited.
      payload:
        type: object
        required: [transferId, userId, attempt, reason]
        properties:
          transferId: { type: string, format: uuid }
          userId:     { $ref: '#/components/schemas/UserRef' }
          attempt:    { type: integer, minimum: 1, maximum: 3 }
          reason:     { type: string, enum: [RECIPIENT_WALLET_NOT_FOUND, TRANSFER_REVERSED] }
    DebitReversed:
      messageId: DebitReversed
      name: DebitReversed
      payload:
        type: object
        required: [transferId, walletId, userId, amount, version]
        properties:
          transferId: { type: string, format: uuid }
          walletId:   { type: string, format: uuid }
          userId:     { $ref: '#/components/schemas/UserRef' }
          amount:     { $ref: '#/components/schemas/Money' }
          version:    { type: integer }

    # ---- Balance read model (ADR 004) ----
    BalanceChanged:
      messageId: BalanceChanged
      name: BalanceChanged
      payload:
        type: object
        required: [walletId, userId, available, version]
        properties:
          walletId:  { type: string, format: uuid }
          userId:    { $ref: '#/components/schemas/UserRef' }
          available: { $ref: '#/components/schemas/Money' }
          version:   { type: integer, description: "Monotonic per wallet; the envelope occurredAt is stored as asOf" }

    # ---- Outcomes: Transfer -> Notification ----
    TransferCompleted:
      messageId: TransferCompleted
      name: TransferCompleted
      payload:
        type: object
        required: [transferId, senderUserId, recipientUserId, amount, currency, completedAt]
        properties:
          transferId:      { type: string, format: uuid }
          senderUserId:    { $ref: '#/components/schemas/UserRef' }
          recipientUserId: { $ref: '#/components/schemas/UserRef' }
          amount:          { $ref: '#/components/schemas/Money' }
          currency:        { type: string, enum: [COP] }
          completedAt:     { type: string, format: date-time }
    TransferRejected:
      messageId: TransferRejected
      name: TransferRejected
      payload:
        type: object
        required: [transferId, senderUserId, recipientUserId, amount, currency, reason]
        properties:
          transferId:      { type: string, format: uuid }
          senderUserId:    { $ref: '#/components/schemas/UserRef' }
          recipientUserId: { $ref: '#/components/schemas/UserRef' }
          amount:          { $ref: '#/components/schemas/Money' }
          currency:        { type: string, enum: [COP] }
          reason:          { type: string, enum: [INSUFFICIENT_FUNDS, SENDER_WALLET_NOT_FOUND] }
    TransferReversed:
      messageId: TransferReversed
      name: TransferReversed
      payload:
        type: object
        required: [transferId, senderUserId, recipientUserId, amount, currency, reason]
        properties:
          transferId:      { type: string, format: uuid }
          senderUserId:    { $ref: '#/components/schemas/UserRef' }
          recipientUserId: { $ref: '#/components/schemas/UserRef' }
          amount:          { $ref: '#/components/schemas/Money' }
          currency:        { type: string, enum: [COP] }
          reason:          { type: string, enum: [RECIPIENT_WALLET_NOT_FOUND, CREDIT_TIMEOUT] }

    # ---- Audit: Gateway, Transfer, Wallet -> Audit ----
    AttemptRecorded:
      messageId: AttemptRecorded
      name: AttemptRecorded
      payload:
        type: object
        required: [eventId, source, stage, outcome, correlationId, occurredAt]
        properties:
          eventId:        { type: string, format: uuid, description: "= envelope messageId; duplicates ignored" }
          source:         { type: string, enum: [GATEWAY, TRANSFER, WALLET] }
          stage:          { type: string, enum: [REQUEST, DEBIT, CREDIT, REVERSAL, TRANSFER] }
          outcome:        { type: string, enum: [ACCEPTED, REJECTED, DUPLICATE, FAILED, COMPENSATED, COMPLETED, APPLIED] }
          reason:         { type: string, nullable: true, description: "See §4.8" }
          transferId:     { type: string, format: uuid, nullable: true }
          userId:         { type: string, format: uuid, nullable: true, description: "null when the Gateway rejects an unauthenticated request" }
          idempotencyKey: { type: string, nullable: true }
          amount:         { type: string, nullable: true, pattern: '^\d{1,17}\.\d{2}$' }
          httpStatus:     { type: integer, nullable: true }
          correlationId:  { type: string, format: uuid }
          occurredAt:     { type: string, format: date-time }
```

### 4.5 Delivery guarantees per queue

| Queue | Ordering | Idempotency | Retry limit (5 s backoff, §4.2) | DLQ |
|---|---|---|---|---|
| `wallet.commands.q` | Not required: `transfer_settlements` decides between credit and reversal, and the ledger allows one entry per transfer and type | `processed_messages` (with stored result) + `UNIQUE(transfer_id, type)` + `transfer_settlements` | 3; saga-level resends in §4.6 | Yes |
| `transfer.wallet-events.q` | Not required: the state machine ignores events that are invalid for the current status | `processed_messages` | 3 | Yes |
| `wallet.balance-projection.q` | Not required: Lua compare-and-set on `version` | Version check | 3; a rebuild job recomputes the cache from `wallet_db` | Yes |
| `notification.transfer-events.q` | Not required | `UNIQUE(transfer_id, event_type, user_id)`; a `SENT` row is never sent again | 3 | Yes |
| `audit.attempts.q` | Total order: single active consumer, so the hash chain stays linear | `audit_event_ids` (PRIMARY KEY `event_id`) | 5 | Yes |

All channels are at-least-once, durable and persistent.

### 4.6 Saga rules (Transfer Service, ADR 002)

1. **Create:** the transfer as `PENDING`, `DebitWallet` and `AttemptRecorded(ACCEPTED)` are written in one transaction (outbox). The relay publishes them.
2. **`WalletDebited`** → `DEBITED`; send `CreditWallet(attempt=1)`.
3. **`DebitRejected`** → `REJECTED` (terminal); publish `TransferRejected`.
4. **`WalletCredited`** (in `DEBITED` or `COMPENSATING`) → `COMPLETED` (terminal); `failureReason` is cleared; publish `TransferCompleted`.
5. **No reply to `CreditWallet` within 10 s** → send a new `CreditWallet` with `attempt + 1`, up to 3 attempts. After the 3rd timeout → `COMPENSATING` with reason `CREDIT_TIMEOUT`.
6. **`CreditFailed(RECIPIENT_WALLET_NOT_FOUND)`** → `COMPENSATING` immediately, with reason `RECIPIENT_WALLET_NOT_FOUND`.
7. **`COMPENSATING`** → send `ReverseDebit` and resend it every 30 s until `DebitReversed` or `WalletCredited` arrives; the reversal is never abandoned. `DebitReversed` → `REVERSED` (terminal); publish `TransferReversed`. `WalletCredited` → rule 4.
8. **Timeout sweeper:** every 2 s it scans non-terminal transfers by `lastCommandAt`. A `PENDING` transfer without a reply for 30 s gets its **original** `DebitWallet` republished (same outbox row and same `messageId`, by clearing `publishedAt`); there is no compensation at this stage, because nothing was debited. `DEBITED` follows rule 5 and `COMPENSATING` follows rule 7, republishing the original `ReverseDebit` row. If Wallet is down, commands wait in the durable queue and the transfer is delayed, not broken.
9. **Late or duplicate events** that are not valid for the current status (for example `CreditFailed(TRANSFER_REVERSED)`, or a re-published `DebitRejected` after `REJECTED`) are acknowledged and ignored.

Each `AttemptRecorded` that Transfer produces (§4.8) is written to the outbox in the same transaction as the state change that causes it.

### 4.7 Wallet operation rules

Each operation runs in one local transaction in `wallet_db`, together with its ledger entry, its `processed_messages` row and its outbox messages (`WalletDebited` / `DebitRejected` / `WalletCredited` / `CreditFailed` / `DebitReversed`, plus `BalanceChanged` and `AttemptRecorded` when they apply).

- **Repeated command:** if `processed_messages` already has the `messageId`, nothing is applied: the stored result (`resultType`, `resultPayload`) is published again with a new `messageId` and no new audit record. A republished `DebitWallet` therefore always gets its original answer: a debit that was rejected is never applied later, even if the sender has received money since then.
- **Debit:** `UPDATE wallets SET balance = balance - :amount, version = version + 1 WHERE user_id = :userId AND balance >= :amount RETURNING ...` plus the `DEBIT` ledger entry → `WalletDebited`. 0 rows updated → `DebitRejected(INSUFFICIENT_FUNDS)`, or `DebitRejected(SENDER_WALLET_NOT_FOUND)` if the wallet does not exist.
- **Credit:** if the recipient has no wallet → `CreditFailed(RECIPIENT_WALLET_NOT_FOUND)`. Otherwise claim `transfer_settlements(transfer_id, CREDITED)`:
  - claimed → `balance + :amount`, `CREDIT` ledger entry, `WalletCredited`;
  - already `CREDITED` (a later `CreditWallet` attempt) → nothing is applied and no new audit record is written; `WalletCredited` is re-emitted;
  - already `REVERSED` → nothing is credited; `CreditFailed(TRANSFER_REVERSED)` (late credit rejected).
- **Reversal (`ReverseDebit`):** claim `transfer_settlements(transfer_id, REVERSED)`. Because the `CREDITED` row is written in the same transaction as the `CREDIT` ledger entry, finding it means the credit was already applied:
  - claimed → sender `balance + :amount`, `REVERSAL` ledger entry, `DebitReversed`;
  - already `CREDITED` → no reversal; `WalletCredited` is re-emitted and the transfer completes;
  - already `REVERSED` → nothing is applied; `DebitReversed` is re-emitted.
- **Idempotency:** `processed_messages` plus `UNIQUE(transfer_id, type)` in the ledger plus `transfer_settlements`. A repeated command never re-applies a balance change.
- The Redis balance is never used to authorize a debit.

### 4.8 Audited attempts

| Source | `stage` / `outcome` (`reason`) | When |
|---|---|---|
| Gateway | `REQUEST` / `REJECTED` (`MISSING_KEY`) | `400 IDEMPOTENCY_KEY_REQUIRED` |
| Gateway | `REQUEST` / `REJECTED` (`KEY_REUSED`) | `422 IDEMPOTENCY_KEY_REUSED` |
| Gateway | `REQUEST` / `REJECTED` (error code) | `POST /transfers` rejected in steps 1–3 or with Redis unavailable: `401`, `403`, `429`, `503` |
| Gateway | `REQUEST` / `DUPLICATE` (`IN_PROGRESS_CONFLICT`) | `409 IDEMPOTENCY_IN_PROGRESS` |
| Gateway | `REQUEST` / `DUPLICATE` (`DUPLICATE_REPLAYED`) | Stored response replayed |
| Gateway | `REQUEST` / `FAILED` (`UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`) | Transfer answered 5xx or did not answer. If Transfer had created the transfer, its own `ACCEPTED` record also exists. |
| Transfer | `REQUEST` / `ACCEPTED` | Transfer created as `PENDING` |
| Transfer | `REQUEST` / `DUPLICATE` (`DUPLICATE_TRANSFER`) | Existing key reached Transfer; existing transfer returned |
| Transfer | `REQUEST` / `REJECTED` (`VALIDATION_ERROR`, `SELF_TRANSFER_NOT_ALLOWED`, `IDEMPOTENCY_KEY_REUSED`) | Invalid request |
| Wallet | `DEBIT` / `APPLIED` or `REJECTED` (`INSUFFICIENT_FUNDS`, `SENDER_WALLET_NOT_FOUND`) | Debit processed |
| Wallet | `CREDIT` / `APPLIED` or `FAILED` (`RECIPIENT_WALLET_NOT_FOUND`, `TRANSFER_REVERSED`) | Credit processed |
| Transfer | `CREDIT` / `FAILED` (`CREDIT_TIMEOUT`) | A `CreditWallet` attempt got no reply within 10 s |
| Wallet | `REVERSAL` / `APPLIED` or `REJECTED` (`ALREADY_CREDITED`) | Reversal processed |
| Transfer | `TRANSFER` / `COMPLETED`, `REJECTED` or `COMPENSATED` | Terminal state reached (`COMPENSATED` = `REVERSED`) |

### 4.9 Notification rules

| Event | Notified users |
|---|---|
| `TransferCompleted` | Sender (A) and recipient (B) |
| `TransferRejected` | Sender |
| `TransferReversed` | Sender |

Notification resolves each `userId` in `recipient_contacts` and sends through `preferredChannel` to the external provider (a mock in Wave 2). Each notification has one row in `notification_log`. A provider failure sets it to `FAILED` and the message is retried from the queue (§4.2); a successful retry updates the same row to `SENT`. A failure never affects the transfer.

---

## 5. Requirements and scenarios (WHEN / THEN)

### Feature 1 — Robust authentication and financial session management

| ID | WHEN | THEN |
|---|---|---|
| F1.1 | A user calls `POST /auth/login` with valid credentials and a `deviceId` | `200` with a 15-minute access token (`sub`, `did`, `sid`, `jti`) and a 7-day refresh token bound to that device. |
| F1.2 | A valid refresh token is presented to `POST /auth/refresh` | `200` with a new pair in the same session; the old refresh token becomes `ROTATED`. |
| F1.3 | A `ROTATED` refresh token is presented again | `401 REFRESH_TOKEN_REUSED`; the session is revoked (`revoked:sid:{sid}`), every access token of that session is rejected with `401 TOKEN_REVOKED`, and the user must log in again. |
| F1.4 | A request carries an access token whose `jti` or `sid` is revoked | The Gateway answers `401 TOKEN_REVOKED`; no service is reached. |
| F1.5 | A user fails login 5 times within 15 minutes | The Gateway sets `lock:login:{email}`; the next login answers `423 ACCOUNT_LOCKED` for 15 minutes without reaching Identity. |
| F1.6 | A user calls `POST /auth/financial-session` with a valid PIN or TOTP code | `201` with a token: scope `transfer:create`, 300 s, bound to the user and device. |
| F1.7 | `POST /transfers` arrives without `X-Financial-Session` | `403 FINANCIAL_SESSION_REQUIRED`; `AttemptRecorded(GATEWAY, REQUEST, REJECTED)`. |
| F1.8 | The financial session belongs to another user or device, or has another scope | `403 FINANCIAL_SESSION_INVALID`. |
| F1.9 | The financial session is older than 5 minutes | `403 FINANCIAL_SESSION_EXPIRED`. |
| F1.10 | A financial session consumed with key K is sent with key K2 | `403 FINANCIAL_SESSION_USED`. |
| F1.11 | A financial session consumed with key K is sent again with key K | Step 3 passes and the idempotency pre-check decides (F3.3, F3.4). |
| F1.12 | Redis is unavailable and any request arrives | The Gateway fails closed: `503` (`IDEMPOTENCY_STORE_UNAVAILABLE` on `POST /transfers`, `SECURITY_STORE_UNAVAILABLE` elsewhere); nothing is routed. |

### Feature 2 — Balance read optimized for extreme speed and high concurrency

| ID | WHEN | THEN |
|---|---|---|
| F2.1 | `GET /wallets/me/balance` and `balance:{userId}` exists | `200` from Redis, without querying PostgreSQL. |
| F2.2 | `GET /wallets/me/balance` and the key is missing | Wallet reads `wallet_db`, stores the value in Redis with its `version` (compare-and-set), and returns `200`. |
| F2.3 | `BalanceChanged` with version 7 arrives and Redis holds version 9 | The event is discarded; Redis keeps version 9. |
| F2.4 | A cache warm-up with version 8 runs after the projection already wrote version 9 | The compare-and-set rejects the write; Redis keeps version 9. |
| F2.5 | A transfer completes | `BalanceChanged` updates the cached balance of both users; the next read returns the new version. |
| F2.6 | The Gateway's checks pass, but Wallet's own Redis read fails or times out | Wallet reads `wallet_db` and returns `200`. |
| F2.7 | Redis shows `100000.00` (stale), PostgreSQL holds `50000.00`, and a debit of `80000.00` arrives | The conditional update affects 0 rows → `DebitRejected(INSUFFICIENT_FUNDS)`. The cache never authorizes a debit. |

### Feature 3 — P2P transfers protected by strict idempotency at the Gateway

| ID | WHEN | THEN |
|---|---|---|
| F3.1 | `POST /transfers` without `Idempotency-Key` | `400 IDEMPOTENCY_KEY_REQUIRED`; nothing reaches Transfer; audited `REJECTED` / `MISSING_KEY`. |
| F3.2 | First request with a new key | `SET NX` stores `IN_PROGRESS` (TTL 86400 s), the request is routed, Transfer answers `202`, and the Gateway stores `COMPLETED` with the response. |
| F3.3 | The user taps "Transfer" 10 times with the same key | Only the first request reaches Transfer. The other nine get `409 IDEMPOTENCY_IN_PROGRESS` (audited `DUPLICATE` / `IN_PROGRESS_CONFLICT`) or the stored `202` (audited `DUPLICATE` / `DUPLICATE_REPLAYED`). Exactly one `DEBIT` ledger entry exists. |
| F3.4 | Retry with the same key and body after completion | The stored `202` is replayed with the same `transferId` and `Idempotent-Replayed: true`. |
| F3.5 | Same key, different body | `422 IDEMPOTENCY_KEY_REUSED`; audited `REJECTED` / `KEY_REUSED`. |
| F3.6 | Redis is unavailable during `POST /transfers` | `503 IDEMPOTENCY_STORE_UNAVAILABLE`; the request is not routed (fail closed). |
| F3.7 | A request with a key that already exists in `transfer_db` reaches Transfer (Redis lost the record or the key expired after 24 h), same body | `UNIQUE(sender_user_id, idempotency_key)` prevents a new transfer; Transfer answers `202` with the existing `transferId` and `Idempotent-Replayed: true`; audited `DUPLICATE` / `DUPLICATE_TRANSFER`. |
| F3.8 | Transfer answers 5xx or does not answer the first request, and the client retries with the same key | The Gateway audits `FAILED` and deletes the key, so the retry is routed; if the transfer had been created, Transfer returns it (F3.7); otherwise it is created now. One transfer at most. |
| F3.9 | The Gateway crashes after routing and before storing `COMPLETED` | The key stays `IN_PROGRESS` until its TTL and retries get `409`. The transfer runs once or does not exist, and Notification informs the user of its outcome. |

### Feature 4 — Saga / Outbox with automatic compensation

| ID | WHEN | THEN |
|---|---|---|
| F4.1 | Valid transfer, sufficient funds, recipient wallet exists | `PENDING → DEBITED → COMPLETED`; one `DEBIT` and one `CREDIT` ledger entry; `TransferCompleted`; A and B are notified. |
| F4.2 | Sender balance < amount | The conditional debit affects 0 rows → `DebitRejected(INSUFFICIENT_FUNDS)` → `REJECTED`; no ledger entry; `TransferRejected`; A is notified. |
| F4.3 | `CreditWallet` gets no reply in 3 attempts of 10 s (network micro-cut) | `COMPENSATING` → `ReverseDebit` → `DebitReversed` → `REVERSED`; A's balance is restored with a `REVERSAL` entry; `TransferReversed`; A is notified. No human intervention. |
| F4.4 | The recipient has no wallet | `CreditFailed(RECIPIENT_WALLET_NOT_FOUND)` → compensation without more attempts → `REVERSED`. |
| F4.5 | The credit timed out on the Transfer side but Wallet had applied it, and `ReverseDebit` arrives | `transfer_settlements` already says `CREDITED`: no reversal; `WalletCredited` is re-emitted; `COMPENSATING → COMPLETED`. |
| F4.6 | A late `CreditWallet` arrives after the reversal was applied | `transfer_settlements` says `REVERSED`: nothing is credited; `CreditFailed(TRANSFER_REVERSED)` is ignored by Transfer. A transfer is never both credited and reversed. |
| F4.7 | Transfer crashes after committing the transfer and its outbox row, before publishing | On restart the relay publishes `DebitWallet` and the saga continues. |
| F4.8 | RabbitMQ delivers the same `DebitWallet` twice | The second delivery creates no ledger entry and no balance change; the stored `WalletDebited` is published again. |
| F4.9 | Concurrent debits on one wallet whose balance covers only one | One is applied; the other gets `DebitRejected(INSUFFICIENT_FUNDS)`; the balance never goes below 0 (`CHECK (balance >= 0)`). |
| F4.10 | Wallet is down while the transfer is `PENDING` | Commands wait in the durable queue; the transfer is delayed, not broken, and completes when Wallet returns. |
| F4.11 | Wallet is down while the transfer is `DEBITED` | After 3 timeouts the transfer moves to `COMPENSATING`. When Wallet returns, the queued `CreditWallet` and `ReverseDebit` both claim `transfer_settlements`; only the first one is applied, and the transfer ends `COMPLETED` or `REVERSED`, never both. |
| F4.12 | Wallet rejected a debit for insufficient funds, the sweeper republished the same `DebitWallet` before Transfer consumed `DebitRejected`, and the sender has received money since then | `processed_messages` already has that `messageId`: nothing is debited and the stored `DebitRejected` is published again; the transfer ends `REJECTED` and the sender keeps the money. |
| F4.13 | `ReverseDebit` cannot be processed because Wallet is down | The transfer stays `COMPENSATING` and `ReverseDebit` is resent every 30 s until `DebitReversed` or `WalletCredited` arrives. |
| F4.14 | After a debit, any failure happens | The transfer always ends `COMPLETED` or `REVERSED`; money is never lost or created. |

### Feature 5 — Immutable append-only record for auditing every financial transaction attempt

| ID | WHEN | THEN |
|---|---|---|
| F5.1 | Any transfer attempt or outcome occurs (accepted, rejected, duplicate, failed, compensated, completed) | `AttemptRecorded` is published to `audit.events` according to §4.8. |
| F5.2 | Audit consumes an `AttemptRecorded` | One row is inserted with the next `seq`, `prevHash` = hash of the previous row and `hash = SHA-256(prevHash \|\| canonicalJson(record without prevHash and hash))`. |
| F5.3 | The same `eventId` is delivered twice | The duplicate is ignored (`audit_event_ids`); the chain is unchanged. |
| F5.4 | Someone runs `UPDATE`, `DELETE` or `TRUNCATE` on `audit_records` | Privileges and the trigger reject it; the row is unchanged. |
| F5.5 | A record was altered (for example by a superuser) and `GET /audit/verify` runs | `valid: false` and `firstInvalidSeq` = the first broken link. |
| F5.6 | An `AUDITOR` calls `GET /audit/records?transferId={id}` | Every record of that transfer, including Gateway rejections and duplicates, ordered by `seq`. |
| F5.7 | A non-`AUDITOR` calls `GET /audit/records` | `403 FORBIDDEN_ROLE`. |
| F5.8 | Audit or Notification is down while transfers run | Transfers are not affected; events wait in their durable queues and are processed when the service returns. |
| F5.9 | Transfer is down or does not answer a `POST /transfers` | The Gateway answers `502` / `504 UPSTREAM_UNAVAILABLE` and records `AttemptRecorded(GATEWAY, REQUEST, FAILED, UPSTREAM_UNAVAILABLE)`; the attempt is in the audit trail even though Transfer never saw it. |
