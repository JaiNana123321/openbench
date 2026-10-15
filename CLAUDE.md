# OpenBench

Implementation of OpenBench for CSDS 393 Software Engineering at Case Western Reserve University: an internal resource marketplace in which a Manager posts one Request for people, Exclusive resources, and Metered capacity, and the system allocates each line under the policy of its Allocation Class and settles it in credits.

Team: Jai Nana, Brian Lujan, Fotih Turopov, Yerassyl Shnazbayev.

## Specification

The SRS, the design document, and the ADRs are maintained in the `swe_csds393` repository; `docs/` holds a snapshot of them. See `docs/README.md` for what is where. Do not edit the snapshot; when the code needs the specification to change, say so.

- Architecture (ADR-0005): a modular monolith. One Spring Boot 3 application built with Maven, one PostgreSQL 16 database with Flyway migrations, and a React + Vite + TypeScript client served by the same process. Nine back-end modules, one package each under `edu.cwru.openbench`: `members`, `inventory`, `settings`, `notifications`, `allocation`, `ledger`, `requests`, `dashboard`, `jobs`. A module exposes only its application services and DTOs, writes only its own tables, and depends only downward; one ArchUnit test enforces this.
- Allocation correctness lives in the database (ADR-0006): exclusion constraints and per-Resource-Type locks, not checks in Java alone.
- Tests run against PostgreSQL 16 through Testcontainers with the production migrations; H2 is not used (design doc Section 6).
- Demo 1 is on 2026-10-23; the rubric is `docs/demo1/Demo1-Rubric.pdf`.

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature-slug>/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`, recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` plus `docs/adr/` at the repo root. See `docs/agents/domain.md`.
