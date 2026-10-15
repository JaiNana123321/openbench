# ADR-0002: A two-part Account with expiring Grants and carried Earnings

Status: Accepted
Date: 2026-09-19
Deciders: Jai Nana, Brian Lujan, Fotih Turopov, Yerassyl Shnazbayev

## Context

Every Team receives a Grant of credits from the Capacity Planner each Period, spends credits on Exclusive and Metered lines, and earns credits by lending its Members. The simplest Ledger has one number per Team that is reset by each Grant. With one expiring bucket, a Team that lends an engineer in the last week of a Period earns credits that vanish days later. Managers would stop lending near the end of every Period, which rebuilds the hoarding the marketplace exists to end.

## Decision

Each Team's Account holds two amounts plus held credits:

- **Granted credits** come from the Capacity Planner's Grant at Period start, or from a mid-Period Grant adjustment, and expire at the end of the Period.
- **Earned credits** come from Earnings for Loans and carry over indefinitely.
- **Held credits** are the locked Quotes of lines currently on Hold. Balance is granted plus earned; Available Balance is Balance minus held.

Spend order: a Charge draws granted credits first, then earned credits, and records the split. A Refund returns credits to the part they were drawn from, in the same proportions. Period rollover zeroes granted credits, records the expired amount on the Statement, posts the new Grant, and leaves earned credits untouched.

No negative Balance, no borrowing, no inter-Team transfers in Release 1. The one exception is the Earning correction posted when a Loan ends early, which may take a Balance below zero; until a Grant or Earning restores it, that Team cannot submit a Request with a Required Exclusive or Metered line.

## Consequences

- Lending is worth the same on the last day of a Period as on the first, so the incentive to lend does not decay.
- The cost is one extra number per Team, a one-sentence spend-order rule, and one column on the Statement.
- Earned credits accumulate across Periods. A Team that lends heavily builds a reserve it can spend on Priority lines; this is intended (see [ADR-0003](0003-priority-by-premium-not-rank.md)).
- The Capacity Planner cannot claw back earned credits; they can only shape future Grants and prices.

## Alternatives rejected

- One bucket, all expiring: simple, but punishes late-Period lending.
- One bucket, nothing expiring: removes the Planner's main lever and lets Grants pile up unused.
- Bank-style overdrafts or transfers between Teams: reintroduce money semantics; transfers are named as Future in the SRS.

Related: `CONTEXT.md` (Account, Balance, Available Balance, Held credits, Grant, Period, Statement); SRS section 4.10 and business rules on spend order, Grant expiry, and affordability.
