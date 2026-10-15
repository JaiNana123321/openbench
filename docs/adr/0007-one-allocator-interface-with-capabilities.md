# ADR-0007: One Allocator interface plus optional capability interfaces

Status: Accepted
Date: 2026-10-05
Deciders: Jai Nana, Brian Lujan, Fotih Turopov, Yerassyl Shnazbayev

## Context

CO-plugin and QA-extensible require that adding an Allocation Class means implementing one interface and changing nothing else: not the existing allocators, the Request lifecycle, or the Ledger. Yet the three classes differ. Exclusive and Metered lines resolve instantly and can be held; a Matched line is driven by approvals and forms Loans. Only Exclusive Bumps, only Matched has a Shortlist, and Exclusive and Metered share the Waitlist.

## Decision

`Allocator` carries the five operations every class needs: `evaluate`, `hold`, `commit`, `release`, and `cancel`. Behavior only some classes have sits on three optional capability interfaces:

- `Waitlistable` (join, leave, waiting lines for freed capacity, expiry): `ExclusiveAllocator` and `MeteredAllocator`.
- `Bumpable` (plan and apply a Bump): `ExclusiveAllocator`.
- `ApprovalDriven` (build a Shortlist, select Candidates, decide approvals, escalate): `MatchedAllocator`.

`AllocatorRegistry` returns the allocator for an Allocation Class and, through `capability(class, type)`, an optional capability. The Requests module asks the registry and never names a concrete allocator. Adding a class means one new `Allocator` bean, plus any capability interfaces it implements; nothing in Requests, Ledger, or the existing allocators changes.

## Consequences

- Requests never branches on the class except through the registry's capability lookup. A line whose class lacks a capability is simply skipped for that step.
- Matched's `hold` is a no-op by contract. A Matched line is never held; it waits as outstanding approvals, bounded by the Objection Window.
- The Stretch item "service tiers and preemption" is `Bumpable` on `MeteredAllocator`, with no change to the interface.
- Capabilities are found at run time, so a class that forgets one is caught by a registry test, not by the compiler.

## Alternatives rejected

- One fat interface with unsupported-operation exceptions: every caller must know which classes throw, and a new class inherits methods that make no sense for it.
- Three unrelated services called by name from Requests: every new class edits the orchestrator, against QA-extensible.
- A generic rules engine: indirection and a configuration language for three classes, with no gain in testability.

Related: [ADR-0005](0005-modular-monolith-direct-calls.md) for the orchestrator that calls the registry; [ADR-0003](0003-priority-by-premium-not-rank.md) for Priority and Bump; [ADR-0004](0004-matched-lines-cost-nothing.md) for Matched. Terms are defined in `CONTEXT.md`.
