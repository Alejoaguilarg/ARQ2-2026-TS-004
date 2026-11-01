# Transfer Service - Base Project with Clean Architecture

## Getting Started

We will explain the different project components starting from external components, continuing with core business components (domain), and finally the application startup and configuration.

Read the article [Clean Architecture — Isolating the Details](https://medium.com/bancolombia-tech/clean-architecture-aislando-los-detalles-4f9530f35d7a)

## Architecture

![Clean Architecture](https://miro.medium.com/max/1400/1*ZdlHz8B0-qu9Y-QO3AXR_w.png)

## Domain

This is the innermost module of the architecture. It belongs to the domain layer and encapsulates business logic and rules through domain models and entities.

## Usecases

This Gradle module belongs to the domain layer. It implements system use cases, defines application logic, and responds to invocations from entry points, orchestrating flows toward entity modules.

## Infrastructure

### Helpers

The helpers section contains general utilities for Driven Adapters and Entry Points.

These utilities are not tied to concrete objects; generics are used to model generic behaviors of different persistence objects. These implementations follow the [Unit of Work and Repository](https://medium.com/@krzychukosobudzki/repository-design-pattern-bc490b256000) design pattern.

These classes cannot exist alone; their behavior must be inherited by **Driven Adapters**.

### Driven Adapters

Driven adapters represent external implementations to our system, such as REST connections, SOAP, databases, flat file reading, and in general any data source we must interact with.

### Entry Points

Entry points represent application entry points or the start of business flows.

## Application

This is the outermost module of the architecture. It is responsible for assembling the different modules, resolving dependencies, and creating beans for use cases (UseCases) automatically, injecting concrete instances of declared dependencies. It also starts the application (this is the only module where we find the `public static void main(String[] args)` function).

**Use case beans are made available automatically through a `@ComponentScan` located in this layer.**
