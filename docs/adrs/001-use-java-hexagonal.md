# ADR 001: Use of Java 21 with Spring Boot and Hexagonal Architecture per Service

**Status:** Accepted — 2026-10-12

- **Context and Problem:**
  Finova is migrating to microservices, and its current chained synchronous
  communication causes partial transfers and double debits. The new
  architecture must make the following possible: The team has decided on five services (Transfer, Wallet, Identity, Audit,
  Notification), and each must:
  - Own its data (Database per Service pattern).
  - Be independently deployable and scalable.
  - Encapsulate complex domain logic: saga orchestration with compensation,
    balance invariants and idempotency rules.
  - Remain maintainable as financial rules evolve.
  - Allow the 5 team members to work in parallel with clear boundaries.

  The team has strong Java/Spring Boot expertise and has chosen Spring Cloud
  Gateway as the API Gateway.

- **Considered Alternatives:**
  - **Layered (Controller-Service-Repository):** familiar and simple for CRUD,
    but domain logic leaks into services, it is hard to test in isolation and
    it couples to Spring. Rejected: Finova's core domain (saga, invariants)
    needs isolation from the framework.
  - **Clean Architecture (Use Cases + Entities):** similar benefits to
    Hexagonal, but more ceremony (a Use Case class per operation) and extra
    indirection. Rejected: Hexagonal maps directly to Spring's
    `@Component`/`@Repository` with less boilerplate.
  - **Modular monolith (Spring Modulith):** single deployment with enforced
    module boundaries. Rejected: it does not provide the independent
    deployment and per-service data ownership the team chose for this
    microservices migration.
  - **Kotlin + Spring Boot:** concise, null-safe, with coroutines. Rejected:
    the team has no Kotlin experience and the learning curve would delay
    Waves 1 and 2; Java 21 (records, pattern matching, virtual threads)
    closes most of the gap.
  - **Go / Node.js / .NET:** various strengths. Rejected: no team expertise
    and onboarding risk.

- **Decision:**
  Each microservice is implemented in **Java 21 with Spring Boot 3.x**
  following **Hexagonal Architecture (Ports & Adapters)**:
  - **Domain core:** aggregates, value objects, domain events and saga
    orchestrators, with no framework dependencies.
  - **Ports (interfaces):** `WalletRepository`, `MessagePublisher`,
    `IdempotencyChecker`, `AuthValidator`.
  - **Adapters:** Spring Data JPA (PostgreSQL), RabbitMQ publisher, Redis
    client, Spring Security filter.

  All services share this structure. The only shared library is a minimal
  `finova-common` kernel (value objects, exceptions, test fixtures).

- **Trade-offs:**
  - *Positives (What we gain):*
    - **Testability:** pure domain logic gives fast unit tests with no Spring
      context.
    - **Replaceable adapters:** RabbitMQ → Kafka, JPA → R2DBC or Redis →
      Valkey without touching the domain.
    - **Parallel work:** 5 developers, 5 services with identical structure and
      minimal merge conflicts.
    - **Spring ecosystem:** Spring Cloud Gateway, Spring Data, Spring
      Security, Actuator and OpenTelemetry integrate natively.
    - **Java 21:** records for commands/events, pattern matching for saga
      state, virtual threads for blocking I/O (RabbitMQ/JDBC).
  - *Negatives (Risks / What we lose):*
    - **Boilerplate (ports + adapters per service):** mitigated with a shared
      `finova-common` scaffolding script and ArchUnit tests that enforce
      layer boundaries.
    - **Distributed transaction complexity:** mitigated at architecture level
      with the Saga + Outbox pattern (ADR 002).
    - **Operational overhead (5 JVMs):** mitigated by deploying everything
      with Docker Compose.

- **Final Justification:**
  Finova's central problem is correctness under failure and retries:
  partial transfers and double debits. Hexagonal Architecture keeps the rules
  that solve it (idempotency checks, saga orchestration with compensation,
  balance invariants) in a framework-free domain core. That lets the team
  test them exhaustively without infrastructure, which directly supports
  mandatory features 3 (idempotent transfers), 4 (Saga/Outbox with automatic
  compensation) and 5 (append-only audit of every attempt). Ports isolate the
  broker, cache and database, so those technologies can change without
  rewriting financial rules. Java 21 with Spring Boot is the best fit because
  the team already masters it and it natively provides the gateway, security,
  observability and messaging integrations the project needs, avoiding the
  delay of a new language. Five services with identical structure let all
  members work in parallel from Wave 1 to Wave 2.

## Validation
- **Week 2:** ArchUnit tests pass on all 5 services (no domain → adapter dependencies).
- **Week 4:** domain unit tests run in under 5 s per service, with 0 Spring context loads.
- **Wave 2:** independent deployment verified through the pipeline (change Wallet, deploy only Wallet).

## References
- design.md → Decisions #1, #2, #6
- specs/transfer/orchestration/spec.md (saga orchestration in domain)
- specs/wallet/balance-read/spec.md (CQRS in Wallet domain)
