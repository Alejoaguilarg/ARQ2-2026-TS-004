# ADR 001: Use Java with Hexagonal Architecture per Service

## Status
Accepted — 2026-10-12

## Context
Finova requires a microservices architecture where each service (Transfer, Wallet, Identity, Audit, Notification) must:
- Own its data (Database per Service pattern)
- Be independently deployable and scalable
- Encapsulate complex domain logic (saga orchestration, balance invariants, idempotency)
- Support long-term maintainability as financial rules evolve
- Allow the team (5 members) to work in parallel with clear boundaries

The team has strong Java/Spring Boot expertise. The case study mandates Spring Boot + Spring Cloud Gateway in the C4 diagram.

## Decision
We will implement each microservice in **Java 21 with Spring Boot 3.x** following **Hexagonal Architecture (Ports & Adapters)**, with a clear separation:
- **Domain core**: aggregates, value objects, domain events, saga orchestrators (no framework deps)
- **Ports (interfaces)**: `WalletRepository`, `MessagePublisher`, `IdempotencyChecker`, `AuthValidator`
- **Adapters**: Spring Data JPA (PostgreSQL), RabbitMQ publisher, Redis client, Spring Security filter

All services share this structure; no shared libraries except a minimal `finova-common` kernel (value objects, exceptions, test fixtures).

## Alternatives Considered

| Alternative | Pros | Cons | Why Rejected |
|-------------|------|------|--------------|
| **Layered (Controller-Service-Repository)** | Familiar, simple for CRUD | Domain logic leaks into services; hard to test in isolation; coupling to Spring | Finova's core domain (saga, invariants) needs isolation from framework |
| **Clean Architecture (Use Cases + Entities)** | Similar benefits to Hexagonal | More ceremony (Use Case classes for every operation); "Interactor" indirection adds noise | Hexagonal maps 1:1 to Spring's `@Component`/`@Repository`; less boilerplate |
| **Modulith (Spring Modulith)** | Single deploy, module boundaries enforced | Doesn't satisfy "independently deployable" requirement for Wave 2 | Case study explicitly requires microservices with separate datastores |
| **Kotlin + Spring Boot** | Concise, null-safety, coroutines | Team has zero Kotlin experience; learning curve delays Wave 1/2 | Java 21 (records, pattern matching, virtual threads) closes most gaps |
| **Go / Node.js / .NET** | Various strengths | No team expertise; hiring/onboarding risk | Team constraint is decisive |

## Consequences
### Positive
- **Testability**: Domain logic pure → unit tests fast, no Spring context needed
- **Replaceable adapters**: Swap RabbitMQ → Kafka, JPA → R2DBC, Redis → Valkey without touching domain
- **Parallel work**: 5 devs = 5 services, each with identical structure, minimal merge conflicts
- **Spring ecosystem**: Spring Cloud Gateway, Spring Data, Spring Security, Actuator, OpenTelemetry integration native
- **Java 21 features**: Records for commands/events, pattern matching for saga state, virtual threads for blocking I/O (RabbitMQ/JDBC)

### Negative (Risks / Mitigations)
- **Boilerplate** (ports + adapters per service) → Mitigation: shared `finova-common` scaffolding script; ArchUnit tests enforce layer boundaries
- **Distributed transactions complexity** → Mitigation: Saga + Outbox pattern (ADR 002) handles this at architecture level
- **Operational overhead (5 JVMs)** → Mitigation: Docker Compose for local; k8s manifests shared template; GraalVM native images if cold-start matters

## Validation
- **Week 2**: ArchUnit test passes on all 5 services (no domain → adapter deps)
- **Week 4**: All domain unit tests run < 5s per service, 0 Spring context loads
- **Wave 2**: Independent deploy verified via pipeline (change Wallet, deploy only Wallet)

## References
- design.md → Decisions #1, #2, #6
- specs/transfer/orchestration/spec.md (saga orchestration in domain)
- specs/wallet/balance-read/spec.md (CQRS in Wallet domain)
