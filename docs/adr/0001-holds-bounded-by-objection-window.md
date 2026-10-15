# ADR-0001: Holds bounded by the Objection Window

Status: Accepted
Date: 2026-09-19
Deciders: Jai Nana, Brian Lujan, Fotih Turopov, Yerassyl Shnazbayev

## Context

A Request spans three Allocation Classes that resolve at different speeds. Exclusive and Metered lines resolve instantly against the Inventory; a Matched line waits for a human, the lending Manager, to approve the Loan. The project feedback asked us to define how a Request behaves when only some of it can be allocated. The four candidate policies were:

1. All-or-nothing at submission: every line must be satisfiable and approved at once. Loses the room every time, because the room is taken while the people are being approved.
2. Best effort: allocate and charge whatever can be allocated now. Charges for GPUs nobody can use when the people are refused.
3. Unbounded reservations: hold the room and the GPUs until the approvals arrive. Blocks other Teams indefinitely on a Request that may never complete.
4. Required and Optional lines with Holds bounded by the Objection Window.

## Decision

We chose policy 4.

- The Manager marks each Request Line Required (default) or Optional. A Request is Allocated only when every Required line is satisfied; Optional lines allocate if they can and never block the outcome.
- When a Request enters Pending, every satisfiable Exclusive and Metered line is placed on Hold: tentative, uncharged, but blocking other Requests exactly as an allocation does. Held credits equal the locked Quote so concurrent Pending Requests cannot jointly overspend.
- A Hold lives at most until the Request's Objection Window lapses. It commits when the Request is Allocated (Holds commit and Charges post in one transaction) and releases whenever the Request leaves Pending without being Allocated.
- The Objection Window starts when the Request first enters Pending, belongs to the Request, and does not restart when a Candidate is replaced. When it lapses, outstanding approvals escalate to the Capacity Planner and no further Hold is placed for that Request; it is Allocated only at a moment when every Required line can be satisfied at once.
- A Request Line is atomic in every class. A Manager who will accept less splits the line and marks part Optional; there is no per-line partial-acceptance flag.

## Consequences

- One mechanism, the Objection Window, bounds both the wait for a silent Manager and the lifetime of every Hold. The SRS can state the guarantee in one sentence: no Hold outlives the Objection Window.
- A Hold does block other Teams from a room that may end up released. We accept this because the block is bounded and the waitlist fills the slot the moment the Hold releases.
- After the window lapses a Request with two waitlisted Required lines is allocated only when both are free at the same moment. This is rarer than line-by-line allocation but is the price of never holding capacity indefinitely.
- The Request state machine needs a Waiting state as the single recovery state for refusals, lapses, and Bumps.

## Alternatives rejected

- Per-line "accept partial, minimum N" flag: more expressive, but a second mechanism beside Required/Optional whose interactions the SRS would have to spell out.
- A throwaway prototype comparing the four policies was offered and not needed for this decision; it remains an option if the SRS wants cited evidence.

Related: [ADR-0004](0004-matched-lines-cost-nothing.md) explains why a refused Matched line costs the requesting Team nothing. Terms are defined in `CONTEXT.md`; the SRS requirements are in section 4.8 of `docs/srs/`.
