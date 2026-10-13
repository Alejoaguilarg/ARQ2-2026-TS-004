# Finova P2P Virtual Wallet — Contract Specification (OpenSpec)

**Version:** 1.0.0
**Status:** Draft
**Generated:** 2026-10-12

---

## Overview

This document defines the contract specification for the Finova P2P Virtual Wallet system. It consolidates all capabilities from the OpenSpec planning phase into a single specification with data schemas, REST APIs, async event contracts, and testable scenarios for each of the 5 mandatory features.

### Services & Responsibilities

| Service | Port | Responsibility | Database |
|---------|------|----------------|----------|
| **API Gateway** | 8080 | Routing, JWT validation, idempotency, rate limiting, financial session enforcement | — (Redis) |
| **Identity Service** | 8081 | Auth, tokens, devices, financial sessions (step-up) | `identity_db` |
| **Wallet Service** | 8082 | Wallet aggregate, ledger, balance CQRS, debit/credit/reversal | `wallet_db` |
| **Transfer Service** | 8083 | Saga orchestration, outbox, transfer state machine | `transfer_db` |
| **Audit Service** | 8084 | Immutable audit log, hash chaining, compliance queries | `audit_db` |
| **Notification Service** | 8085 | Push/email/SMS from outcome events | `notification_db` |

### Infrastructure

| Component | Technology | Purpose |
|-----------|------------|---------|
| Message Broker | RabbitMQ 3.12 (quorum queues) | Async commands/events between services |
| Cache / Idempotency / Sessions | Redis 7 (cluster) | Idempotency keys, balance cache, token revocation, rate limiting |
| Databases | PostgreSQL 16 (per service) | ACID persistence per bounded context |
| Observability | OpenTelemetry + Jaeger + Prometheus/Grafana | Distributed tracing, metrics, logging |

---

## Data Schemas

### Transfer

```yaml
Transfer:
  type: object
  required: [transferId, idempotencyKey, fromUserId, toUserId, amount, currency, status, createdAt, updatedAt]
  properties:
    transferId:
      type: string
      format: uuid
      description: Unique transfer identifier
    idempotencyKey:
      type: string
      maxLength: 64
      description: Client-generated key for idempotency
    fromUserId:
      type: string
      format: uuid
      description: Sender user ID
    toUserId:
      type: string
      format: uuid
      description: Receiver user ID
    amount:
      type: integer
      minimum: 1
      description: Amount in minor units (cents)
    currency:
      type: string
      enum: [COP, USD, EUR]
      default: COP
    status:
      type: string
      enum: [PENDING, DEBITED, CREDITED, COMPLETED, REVERSED, REJECTED]
    createdAt:
      type: string
      format: date-time
    updatedAt:
      type: string
      format: date-time
    completedAt:
      type: string
      format: date-time
      nullable: true
```

### Wallet

```yaml
Wallet:
  type: object
  required: [walletId, userId, balance, currency, version, createdAt, updatedAt]
  properties:
    walletId:
      type: string
      format: uuid
    userId:
      type: string
      format: uuid
    balance:
      type: integer
      minimum: 0
      description: Current balance in minor units
    currency:
      type: string
      enum: [COP, USD, EUR]
    version:
      type: integer
      minimum: 1
      description: Optimistic lock version
    createdAt:
      type: string
      format: date-time
    updatedAt:
      type: string
      format: date-time
```

### LedgerEntry

```yaml
LedgerEntry:
  type: object
  required: [entryId, walletId, transferId, type, amount, balanceBefore, balanceAfter, createdAt]
  properties:
    entryId:
      type: string
      format: uuid
    walletId:
      type: string
      format: uuid
    transferId:
      type: string
      format: uuid
      description: Idempotency key for wallet operations
    type:
      type: string
      enum: [DEBIT, CREDIT, REVERSAL]
    amount:
      type: integer
      minimum: 1
    balanceBefore:
      type: integer
      minimum: 0
    balanceAfter:
      type: integer
      minimum: 0
    createdAt:
      type: string
      format: date-time
```

### FinancialSession

```yaml
FinancialSession:
  type: object
  required: [sessionId, userId, deviceId, transferAmount, expiresAt, used]
  properties:
    sessionId:
      type: string
      format: uuid
    userId:
      type: string
      format: uuid
    deviceId:
      type: string
      maxLength: 128
    transferAmount:
      type: integer
      minimum: 1
    expiresAt:
      type: string
      format: date-time
    used:
      type: boolean
      default: false
```

### AuditRecord

```yaml
AuditRecord:
  type: object
  required: [auditId, eventType, transferId, userId, payload, hash, prevHash, createdAt]
  properties:
    auditId:
      type: string
      format: uuid
    eventType:
      type: string
      enum: [TRANSFER_ACCEPTED, TRANSFER_REJECTED, TRANSFER_DUPLICATE, WALLET_DEBITED, WALLET_CREDITED, WALLET_REVERSED, TRANSFER_COMPLETED, TRANSFER_REVERSED, SESSION_CREATED, SESSION_USED, AUTH_FAILURE]
    transferId:
      type: string
      format: uuid
      nullable: true
    userId:
      type: string
      format: uuid
    payload:
      type: object
      description: Full event payload snapshot
    hash:
      type: string
      pattern: '^[a-f0-9]{64}$'
      description: SHA-256(payload || prevHash)
    prevHash:
      type: string
      pattern: '^[a-f0-9]{64}$'
      nullable: true
    createdAt:
      type: string
      format: date-time
```


---

## REST APIs (OpenAPI 3.0)

### POST /transfers — Initiate P2P Transfer

```yaml
openapi: 3.0.3
info:
  title: Finova Transfer API
  version: 1.0.0
paths:
  /transfers:
    post:
      summary: Initiate a P2P transfer
      operationId: createTransfer
      security:
        - BearerAuth: []
        - FinancialSessionAuth: []
      parameters:
        - name: Idempotency-Key
          in: header
          required: true
          schema:
            type: string
            maxLength: 64
          description: Client-generated idempotency key
        - name: X-Financial-Session
          in: header
          required: true
          schema:
            type: string
            format: uuid
          description: Financial session token from step-up auth
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [toUserId, amount, currency]
              properties:
                toUserId:
                  type: string
                  format: uuid
                amount:
                  type: integer
                  minimum: 1
                currency:
                  type: string
                  enum: [COP, USD, EUR]
                  default: COP
                description:
                  type: string
                  maxLength: 255
                  nullable: true
      responses:
        '202':
          description: Transfer accepted for processing
          headers:
            Location:
              schema:
                type: string
                format: uri
              description: Status polling endpoint
          content:
            application/json:
              schema:
                type: object
                required: [transferId, status, statusUrl]
                properties:
                  transferId:
                    type: string
                    format: uuid
                  status:
                    type: string
                    enum: [PENDING]
                  statusUrl:
                    type: string
                    format: uri
              example:
                transferId: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                status: "PENDING"
                statusUrl: "/transfers/a1b2c3d4-e5f6-7890-abcd-ef1234567890/status"
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '409':
          $ref: '#/components/responses/IdempotencyConflict'
        '422':
          $ref: '#/components/responses/UnprocessableEntity'
        '429':
          $ref: '#/components/responses/TooManyRequests'
        '500':
          $ref: '#/components/responses/InternalError'

  /transfers/{transferId}/status:
    get:
      summary: Get transfer status
      operationId: getTransferStatus
      security:
        - BearerAuth: []
      parameters:
        - name: transferId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Current transfer status
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TransferStatusResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '404':
          $ref: '#/components/responses/NotFound'

  /balance:
    get:
      summary: Get current wallet balance (cached)
      operationId: getBalance
      security:
        - BearerAuth: []
      responses:
        '200':
          description: Current balance
          content:
            application/json:
              schema:
                type: object
                required: [walletId, balance, currency, version, asOf]
                properties:
                  walletId:
                    type: string
                    format: uuid
                  balance:
                    type: integer
                    minimum: 0
                  currency:
                    type: string
                  version:
                    type: integer
                  asOf:
                    type: string
                    format: date-time
        '401':
          $ref: '#/components/responses/Unauthorized'

components:
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
    FinancialSessionAuth:
      type: apiKey
      in: header
      name: X-Financial-Session

  responses:
    BadRequest:
      description: Invalid request
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    Unauthorized:
      description: Invalid or missing authentication
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    Forbidden:
      description: Missing or invalid financial session
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    IdempotencyConflict:
      description: Idempotency key conflict or replay
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    UnprocessableEntity:
      description: Business rule violation (e.g., insufficient funds)
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    TooManyRequests:
      description: Rate limit exceeded
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    InternalError:
      description: Internal server error
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    NotFound:
      description: Resource not found
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'

  schemas:
    TransferStatusResponse:
      type: object
      required: [transferId, status, createdAt, updatedAt]
      properties:
        transferId:
          type: string
          format: uuid
        status:
          type: string
          enum: [PENDING, DEBITED, CREDITED, COMPLETED, REVERSED, REJECTED]
        fromUserId:
          type: string
          format: uuid
        toUserId:
          type: string
          format: uuid
        amount:
          type: integer
        currency:
          type: string
        createdAt:
          type: string
          format: date-time
        updatedAt:
          type: string
          format: date-time
        completedAt:
          type: string
          format: date-time
          nullable: true
    ErrorResponse:
      type: object
      required: [code, message, timestamp]
      properties:
        code:
          type: string
        message:
          type: string
        details:
          type: object
          nullable: true
        timestamp:
          type: string
          format: date-time
```

---

## Async Event Contracts (AsyncAPI 2.6)

### Wallet Commands (Transfer Service -> Wallet Service)

```yaml
asyncapi: 2.6.0
info:
  title: Finova Wallet Commands
  version: 1.0.0
channels:
  wallet.commands:
    publish:
      summary: Commands sent to Wallet Service
      messages:
        DebitWallet:
          name: DebitWallet
          payload:
            $ref: '#/components/schemas/DebitWallet'
        CreditWallet:
          name: CreditWallet
          payload:
            $ref: '#/components/schemas/CreditWallet'
        ReverseDebit:
          name: ReverseDebit
          payload:
            $ref: '#/components/schemas/ReverseDebit'
components:
  schemas:
    DebitWallet:
      type: object
      required: [transferId, walletId, amount, idempotencyKey]
      properties:
        transferId:
          type: string
          format: uuid
        walletId:
          type: string
          format: uuid
        amount:
          type: integer
          minimum: 1
        idempotencyKey:
          type: string
          maxLength: 64
    CreditWallet:
      type: object
      required: [transferId, walletId, amount, idempotencyKey]
      properties:
        transferId:
          type: string
          format: uuid
        walletId:
          type: string
          format: uuid
        amount:
          type: integer
          minimum: 1
        idempotencyKey:
          type: string
          maxLength: 64
    ReverseDebit:
      type: object
      required: [transferId, walletId, originalAmount, idempotencyKey]
      properties:
        transferId:
          type: string
          format: uuid
        walletId:
          type: string
          format: uuid
        originalAmount:
          type: integer
          minimum: 1
        idempotencyKey:
          type: string
          maxLength: 64
```

### Wallet Events (Wallet Service -> Broker)

```yaml
asyncapi: 2.6.0
info:
  title: Finova Wallet Events
  version: 1.0.0
channels:
  wallet.events:
    publish:
      summary: Events published by Wallet Service
      messages:
        WalletDebited:
          name: WalletDebited
          payload:
            $ref: '#/components/schemas/WalletDebited'
        WalletCredited:
          name: WalletCredited
          payload:
            $ref: '#/components/schemas/WalletCredited'
        WalletReversed:
          name: WalletReversed
          payload:
            $ref: '#/components/schemas/WalletReversed'
        BalanceChanged:
          name: BalanceChanged
          payload:
            $ref: '#/components/schemas/BalanceChanged'
        InsufficientFunds:
          name: InsufficientFunds
          payload:
            $ref: '#/components/schemas/InsufficientFunds'
components:
  schemas:
    WalletDebited:
      type: object
      required: [transferId, walletId, amount, balanceAfter, version, timestamp]
      properties:
        transferId:
          type: string
          format: uuid
        walletId:
          type: string
          format: uuid
        amount:
          type: integer
        balanceAfter:
          type: integer
        version:
          type: integer
        timestamp:
          type: string
          format: date-time
    WalletCredited:
      type: object
      required: [transferId, walletId, amount, balanceAfter, version, timestamp]
      properties:
        transferId:
          type: string
          format: uuid
        walletId:
          type: string
          format: uuid
        amount:
          type: integer
        balanceAfter:
          type: integer
        version:
          type: integer
        timestamp:
          type: string
          format: date-time
    WalletReversed:
      type: object
      required: [transferId, walletId, amount, balanceAfter, version, timestamp]
      properties:
        transferId:
          type: string
          format: uuid
        walletId:
          type: string
          format: uuid
        amount:
          type: integer
        balanceAfter:
          type: integer
        version:
          type: integer
        timestamp:
          type: string
          format: date-time
    BalanceChanged:
      type: object
      required: [walletId, balance, version, timestamp]
      properties:
        walletId:
          type: string
          format: uuid
        balance:
          type: integer
        version:
          type: integer
        timestamp:
          type: string
          format: date-time
    InsufficientFunds:
      type: object
      required: [transferId, walletId, requestedAmount, currentBalance, timestamp]
      properties:
        transferId:
          type: string
          format: uuid
        walletId:
          type: string
          format: uuid
        requestedAmount:
          type: integer
        currentBalance:
          type: integer
        timestamp:
          type: string
          format: date-time
```

### Transfer Events (Transfer Service -> Broker)

```yaml
asyncapi: 2.6.0
info:
  title: Finova Transfer Events
  version: 1.0.0
channels:
  transfer.events:
    publish:
      summary: Transfer lifecycle events
      messages:
        TransferCompleted:
          name: TransferCompleted
          payload:
            $ref: '#/components/schemas/TransferCompleted'
        TransferReversed:
          name: TransferReversed
          payload:
            $ref: '#/components/schemas/TransferReversed'
        TransferRejected:
          name: TransferRejected
          payload:
            $ref: '#/components/schemas/TransferRejected'
components:
  schemas:
    TransferCompleted:
      type: object
      required: [transferId, fromUserId, toUserId, amount, currency, timestamp]
      properties:
        transferId:
          type: string
          format: uuid
        fromUserId:
          type: string
          format: uuid
        toUserId:
          type: string
          format: uuid
        amount:
          type: integer
        currency:
          type: string
        timestamp:
          type: string
          format: date-time
    TransferReversed:
      type: object
      required: [transferId, fromUserId, toUserId, amount, currency, reason, timestamp]
      properties:
        transferId:
          type: string
          format: uuid
        fromUserId:
          type: string
          format: uuid
        toUserId:
          type: string
          format: uuid
        amount:
          type: integer
        currency:
          type: string
        reason:
          type: string
        timestamp:
          type: string
          format: date-time
    TransferRejected:
      type: object
      required: [transferId, fromUserId, toUserId, amount, currency, reason, timestamp]
      properties:
        transferId:
          type: string
          format: uuid
        fromUserId:
          type: string
          format: uuid
        toUserId:
          type: string
          format: uuid
        amount:
          type: integer
        currency:
          type: string
        reason:
          type: string
        timestamp:
          type: string
          format: date-time
```

### Audit Events (All Services -> Audit Service)

```yaml
asyncapi: 2.6.0
info:
  title: Finova Audit Events
  version: 1.0.0
channels:
  audit.events:
    publish:
      summary: Audit events from all services
      messages:
        AuditEvent:
          name: AuditEvent
          payload:
            $ref: '#/components/schemas/AuditEvent'
components:
  schemas:
    AuditEvent:
      type: object
      required: [eventType, transferId, userId, payload, timestamp]
      properties:
        eventType:
          type: string
          enum: [TRANSFER_ACCEPTED, TRANSFER_REJECTED, TRANSFER_DUPLICATE, WALLET_DEBITED, WALLET_CREDITED, WALLET_REVERSED, TRANSFER_COMPLETED, TRANSFER_REVERSED, SESSION_CREATED, SESSION_USED, AUTH_FAILURE]
        transferId:
          type: string
          format: uuid
          nullable: true
        userId:
          type: string
          format: uuid
        payload:
          type: object
        timestamp:
          type: string
          format: date-time
```

## Requirements & Scenarios (WHEN/THEN)

### Feature 1: Robust Authentication & Financial Session Management

| Scenario | WHEN | THEN |
|----------|------|------|
| **F1.1** Login issues rotating tokens | User authenticates with valid credentials | Access token (15 min) + rotating refresh token (7 days) issued |
| **F1.2** Refresh token reuse triggers revocation | Same refresh token presented twice | All user tokens revoked, audit event recorded, re-login required |
| **F1.3** Financial session required for transfer | POST /transfers without `X-Financial-Session` header | 403 Forbidden with `FINANCIAL_SESSION_REQUIRED` |
| **F1.4** Financial session created via step-up | User completes PIN/OTP challenge | Short-lived (5 min), single-use session token bound to amount/device issued |
| **F1.5** Revoked token rejected immediately | Access token JTI in Redis revocation set | 401 Unauthorized on next request |
| **F1.6** Rate limiting on auth endpoints | 5 failed login attempts in 15 min | 429 Too Many Requests, 30 min lockout |

### Feature 2: Balance Read Optimized for Extreme Speed & Concurrency

| Scenario | WHEN | THEN |
|----------|------|------|
| **F2.1** Balance read served from Redis | GET /balance with valid token | Balance returned in < 5ms p99, no DB query |
| **F2.2** Cache miss falls back to DB | Redis has no entry for wallet | DB queried, cache populated, balance returned |
| **F2.3** Stale event discarded | BalanceChanged v3 received, cache at v5 | Event discarded, cache unchanged |
| **F2.4** Read reflects transfer within 100ms | Transfer completes, BalanceChanged published | Subsequent GET /balance returns updated balance |
| **F2.5** Concurrent debits validated against DB | Two simultaneous DebitWallet for same wallet | Optimistic locking in DB, only one succeeds if insufficient balance |

### Feature 3: P2P Transfers with Strict Idempotency at Gateway

| Scenario | WHEN | THEN |
|----------|------|------|
| **F3.1** Missing idempotency key rejected | POST /transfers without `Idempotency-Key` | 400 Bad Request, `IDEMPOTENCY_KEY_REQUIRED` |
| **F3.2** First request with new key proceeds | Valid key + body, key not in Redis | Key + SHA-256(body) stored (TTL 24h), 202 Accepted |
| **F3.3** Duplicate key with identical body returns stored response | Retry with same key + identical body | 409 Conflict with current status, or replayed 202 if completed |
| **F3.4** Duplicate key with different body rejected | Same key, different request body | 409 Conflict, `IDEMPOTENCY_KEY_CONFLICT` |
| **F3.5** Defense in depth: DB unique constraint | Duplicate bypasses Gateway | 409 Conflict from Transfer Service unique constraint |

### Feature 4: Saga/Outbox with Automatic Compensation

| Scenario | WHEN | THEN |
|----------|------|------|
| **F4.1** Successful transfer completes all steps | Valid transfer, sufficient funds | DEBITED -> CREDITED -> COMPLETED, both balances updated |
| **F4.2** Insufficient funds rejects transfer | Sender balance < amount | REJECTED, no balance changes, audit logged |
| **F4.3** Credit failure triggers compensation | CreditWallet fails after 3 retries + timeout | ReverseDebit published, sender balance restored, REVERSED |
| **F4.4** Network failure mid-saga triggers compensation | Broker delivery fails permanently | Saga timeout (30s) -> ReverseDebit -> REVERSED |
| **F4.5** Duplicate wallet commands ignored | Wallet receives same transferId twice | Second command returns success without re-applying |
| **F4.6** Outbox guarantees message publication | Transfer created in DB | DebitWallet published to RabbitMQ after transaction commits |

### Feature 5: Immutable Append-Only Audit of Every Attempt

| Scenario | WHEN | THEN |
|----------|------|------|
| **F5.1** Every attempt produces audit event | Any transfer request (accepted/rejected/duplicate/failed/compensated) | AuditEvent published to broker |
| **F5.2** Audit record includes hash chain | AuditService persists event | Row inserted with `hash = SHA256(payload || prev_hash)` |
| **F5.3** Tamper detection via verification | Auditor recomputes hashes from genesis | Any modified/deleted/inserted row detected |
| **F5.4** Async audit adds no latency | Transfer saga completes | 202 returned to client before audit persisted |
| **F5.5** Direct delete blocked by DB | SQL DELETE on audit table | Permission denied, row unchanged |
| **F5.6** Compliance query by transferId | GET /audit?transferId=xxx | All events for transfer in chronological order |

---

## Delivery Guarantees

| Channel | Ordering | Idempotency | Retries | DLQ |
|---------|----------|-------------|---------|-----|
| `wallet.commands` | Per transferId (single partition) | Consumer-side (transferId) | 3x exponential backoff | Yes |
| `wallet.events` | Per walletId | Consumer-side (version) | 3x | Yes |
| `transfer.events` | Per transferId | Consumer-side (transferId) | 3x | Yes |
| `audit.events` | Global (single partition) | Natural (insert-only) | 5x | Yes |

---

## Error Codes Reference

| Code | HTTP | Description |
|------|------|-------------|
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Missing Idempotency-Key header |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | Key reused with different body |
| `FINANCIAL_SESSION_REQUIRED` | 403 | Missing/invalid X-Financial-Session |
| `FINANCIAL_SESSION_EXPIRED` | 403 | Session token expired |
| `FINANCIAL_SESSION_USED` | 403 | Session token already consumed |
| `INSUFFICIENT_FUNDS` | 422 | Sender balance < transfer amount |
| `TRANSFER_NOT_FOUND` | 404 | Transfer ID does not exist |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests |
| `INTERNAL_ERROR` | 500 | Unexpected server error |
