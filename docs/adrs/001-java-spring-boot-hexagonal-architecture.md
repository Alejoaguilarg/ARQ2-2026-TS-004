# ADR 001: Use of Java 21 with Spring Boot and Hexagonal Architecture per Service

- **Status:** Accepted
- **Date:** 2026-10-16

## Context and Problem

Finova is migrating to microservices, and its current chained synchronous communication causes partial transfers and double debits. The team has decided on five services (Transfer, Wallet, Identity, Audit, Notification), and each must own its data (Database per Service), be independently deployable and scalable, encapsulate complex domain logic (saga orchestration with compensation, balance invariants, idempotency rules), remain maintainable as financial rules evolve, and allow the 5 team members to work in parallel with clear boundaries. The team has strong Java/Spring Boot expertise and has chosen Spring Cloud Gateway as the API Gateway.

## Considered Alternatives

- **Layered (Controller-Service-Repository):** simple, but domain logic leaks into services and couples to Spring; Finova's saga and invariants need isolation from the framework.
- **Clean Architecture with its full set of layers (use cases, entities, presenters, interactors):** similar benefits, but more ceremony and indirection than Hexagonal for the same result.
- **Modular monolith (Spring Modulith):** does not provide the independent deployment and per-service data ownership the team chose.
- **Kotlin + Spring Boot:** the team has no Kotlin experience, and the learning curve would delay Waves 1 and 2.
- **Go / Node.js / .NET:** no team expertise and onboarding risk.

## Decision

Each microservice is implemented in Java 21 with Spring Boot 3.x following Hexagonal Architecture (Ports & Adapters): a framework-free domain core (aggregates, value objects, domain events, saga orchestrators), ports (`WalletRepository`, `MessagePublisher`, `IdempotencyChecker`, `AuthValidator`) and adapters (Spring Data JPA with PostgreSQL, RabbitMQ publisher, Redis client, Spring Security filter). All services share this structure, with only a minimal `finova-common` kernel as shared library.

Services are scaffolded with the Bancolombia Scaffold Clean Architecture Gradle plugin because it generates exactly this ports-and-adapters layout: `domain/model` (aggregates and port interfaces, called *gateways*), `domain/usecase` (application services), `infrastructure/driven-adapters` (outbound adapters) and `infrastructure/entry-points` (inbound adapters). We use it as a scaffolding tool only; the architectural style is Hexagonal.

## Trade-offs

**Positives (what we gain)**
- Testability: pure domain logic gives fast unit tests without a Spring context.
- Replaceable adapters (RabbitMQ to Kafka, JPA to R2DBC, Redis to Valkey) without touching the domain.
- Parallel work: 5 developers, 5 services with identical structure and minimal merge conflicts.
- Native Spring ecosystem: Cloud Gateway, Data, Security, Actuator and OpenTelemetry.
- Java 21: records for commands and events, pattern matching for saga state, virtual threads for blocking I/O.

**Negatives (risks / what we lose)**
- Boilerplate (ports and adapters per service): mitigated with the scaffolding plugin and ArchUnit tests that enforce layer boundaries.
- Distributed transaction complexity: mitigated at architecture level with the Saga + Outbox pattern (ADR 002).
- Operational overhead of 5 JVMs: mitigated by deploying everything with Docker Compose.

## Final Justification

Finova's central problem is correctness under failure and retries: partial transfers and double debits. Hexagonal Architecture keeps the rules that solve it (idempotency checks, saga orchestration with compensation, balance invariants) in a framework-free domain core. That lets the team test them exhaustively without infrastructure, which directly supports mandatory features 3, 4 and 5. Ports isolate the broker, cache and database, so those technologies can change without rewriting financial rules. Java 21 with Spring Boot is the best fit because the team already masters it and it natively provides the gateway, security, observability and messaging integrations the project needs.
