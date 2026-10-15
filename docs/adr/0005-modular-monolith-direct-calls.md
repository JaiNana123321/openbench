# ADR-0005: One process, nine modules, direct calls

Status: Accepted
Date: 2026-10-05
Deciders: Jai Nana, Brian Lujan, Fotih Turopov, Yerassyl Shnazbayev

## Context

Four students have one semester and one on-premises server. The SRS already fixes one Spring Boot process and one PostgreSQL database. The requirements that cannot bend are correctness and atomicity across three allocators and the Ledger (QA-correct, QA-atomic, CO-tx). The standing instruction is to keep the architecture as simple as possible while meeting them.

## Decision

- **A modular monolith.** One Spring Boot 3 application, one PostgreSQL 16 database, and one React application served by the same process. Nine back-end modules, one Java package each under `edu.cwru.openbench`: `members`, `inventory`, `settings`, `notifications`, `allocation`, `ledger`, `requests`, `dashboard`, `jobs`. Modules call each other's application services directly, inside the caller's transaction. No events, no broker.
- **Dependency rule.** (1) A module's only public surface is its application service classes and the DTOs they take and return; entities and repositories are package-private. (2) A module writes only its own tables. (3) Dependencies point downward in the layer order below, with no cycles. (4) The one upward hook is the port `ReductionGuard`, declared by Inventory and implemented by Allocation, so Inventory can refuse a capacity reduction below future usage without depending on Allocation. (5) Dashboard is read-only: it may query any table through SQL views and writes nothing.
- **Layers, top to bottom.** The web client; `requests`, `dashboard`, `jobs`; `allocation`, `ledger`; `inventory`, `members`, `settings`, `notifications`. Inside a module: controller (REST), application service, domain (entities and pure rules), repository (Spring Data JPA). Two modules in one layer may depend on each other in one direction only (Jobs on Requests, Settings on Inventory).
- **Enforcement.** One ArchUnit test class checks visibility, layer order, and cycles, and fails the build on a violation. Code review covers the rest.
- **Orchestration.** `RequestLifecycleService` in the Requests module calls Allocation and Ledger directly, in one `@Transactional` method per user or job action, so Holds commit and Charges post in the same transaction that changes the Request's state.

## Consequences

- One transaction boundary gives atomicity for free: no compensation logic, no half-allocated Request to repair.
- Adding a module is adding a package.
- A second instance later needs only a job lock for the two scheduled jobs, because allocation correctness lives in the database ([ADR-0006](0006-allocation-correctness-in-the-database.md)).
- Modules share a database, so discipline replaces physical isolation. ArchUnit sees imports and visibility, not a native SQL write to another module's table; the sole-writer rule for SQL is a code-review item.

## Alternatives rejected

- Domain events between modules: a listener that runs after commit would break "Holds commit and Charges post in one transaction", and events add a second failure mode, a handler that fails after the sender has committed.
- Hexagonal ports everywhere: ceremony without a second adapter in sight. The one port, `ReductionGuard`, stays because it breaks a cycle.
- Microservices: distributed transactions for a system with about 50 users.
- Spring Modulith: a dependency for what one ArchUnit test does.

Related: [ADR-0006](0006-allocation-correctness-in-the-database.md) for the locking rules; [ADR-0007](0007-one-allocator-interface-with-capabilities.md) for the allocator interface the orchestrator calls. Terms are defined in `CONTEXT.md`.
