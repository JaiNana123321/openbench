# ADR-0006: Allocation correctness enforced in PostgreSQL; tests run on PostgreSQL

Status: Accepted
Date: 2026-10-05
Deciders: Jai Nana, Brian Lujan, Fotih Turopov, Yerassyl Shnazbayev

## Context

QA-correct says that under concurrent submissions no Exclusive instance is double-booked and no Metered type exceeds its Pool Capacity in any hour, with allocation serialized per Resource Type in the database. The two invariants differ in shape. Exclusive is pairwise, which an exclusion constraint states exactly. Metered is a sum over many rows, which no constraint can state.

The research note (`docs/research/allocation-concurrency-postgres.md`) tested both on PostgreSQL 16.15 with two concurrent sessions. Without a lock, two 5-unit lines against a pool of 8 both committed. With a row lock on the type at READ COMMITTED, the second refused. With the same lock at REPEATABLE READ, both committed again (10 of 8), because the snapshot froze before the wait. H2 rejected the extension, range types, and `EXCLUDE` outright.

## Decision

- **Exclusive.** One row per occurrence in `exclusive_occurrence`, with a partial exclusion constraint (`btree_gist`); Released, Bumped, and Expired rows stay as history and block nothing:

  ```sql
  EXCLUDE USING gist (resource_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
    WHERE (state IN ('HELD','ALLOCATED'))
  ```

- **Type lock, which Metered rests on.** Every allocation attempt, Exclusive or Metered, runs in one READ COMMITTED transaction that first locks the `resource_type` row of each type it touches (`SELECT ... FOR UPDATE`, ascending id, through `AllocationLock`), then the Team's `account` row, then checks every hour (allocated plus held plus requested within Pool Capacity, and within the Team's Quota when set), then writes. A Bump marks the old rows Bumped and flushes before it inserts the Priority occurrence. The isolation level is never raised.
- **Guarantee.** "Every allocation decision runs in one PostgreSQL READ COMMITTED transaction that first locks the row of each Resource Type it touches, in ascending id order, so checks and writes for the same type never interleave; an exclusion constraint additionally makes it impossible for two Held or Allocated occurrences on the same Exclusive instance to overlap."
- **Schema and tests.** Flyway runs SQL migrations at start-up and Hibernate only validates. Tests run on PostgreSQL 16 in a container (Testcontainers) with the same migrations.

## Consequences

- No double-booking on any code path, including jobs and hand-written SQL. A constraint violation signals a bug, not normal flow.
- Metered correctness depends on every writer taking the type lock: Hold, allocate, Bump, waitlist fill, capacity reduction. ArchUnit cannot check this, so it is a code-review item behind one named helper, plus a two-thread integration test.
- REPEATABLE READ or SERIALIZABLE must never be set on this path.
- CO-2 and SI-db in the SRS changed from H2 to PostgreSQL 16 in a container (SRS 0.3). Developers need Docker to run tests; the application already ships as a Docker image.

## Alternatives rejected

- SERIALIZABLE with retry: correct, but it failed transactions on unrelated types in the local check and needs a retry wrapper, and Spring Retry is archived.
- Advisory locks: the same guarantee as the row lock, but invisible in the schema.
- Hour-bucket table with CHECK: a second representation of the bookings to keep in sync.
- Single-writer queue: puts the guarantee outside the database, against QA-correct's wording.
- H2 for tests: cannot run `btree_gist`, exclusion constraints, range types, or the native sum query, and its SERIALIZABLE is documented as incomplete.

Related: [ADR-0001](0001-holds-bounded-by-objection-window.md) for Holds; [ADR-0005](0005-modular-monolith-direct-calls.md) for the single transaction. Terms are defined in `CONTEXT.md`.
