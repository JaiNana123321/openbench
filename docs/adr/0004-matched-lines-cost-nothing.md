# ADR-0004: Matched lines cost nothing; lending earns a flat Day Rate

Status: Accepted
Date: 2026-09-19
Deciders: Jai Nana, Brian Lujan, Fotih Turopov, Yerassyl Shnazbayev

## Context

The Ledger charges credits for Exclusive and Metered lines. The obvious symmetric design also charges for people. But the marketplace exists because Managers hoard headcount; a price on borrowing people would make a Manager who needs help weigh it against rooms and compute, and would push Teams back to keeping their own people busy rather than lending them. The proposal called the Ledger "deliberately lopsided" for this reason.

## Decision

- A Matched line has no Unit Price and costs the requesting Team nothing. The lending Manager's approval, not a price, is the gate.
- Lending a Person earns the lending Team a flat **Day Rate** per person-day of the Loan, set once by the Capacity Planner and identical for every Person regardless of skill. A person-day is one Monday-to-Friday calendar day inside the Loan's window.
- Earnings post when the Loan forms, in the same step in which Holds commit and Charges post. Nothing posts while the Request is Pending.
- If a Loan ends early the Earning is corrected to the days actually lent by a correcting Earning Transaction. No Refund Schedule applies because nothing was charged.
- Earned credits go to the carried part of the Account ([ADR-0002](0002-two-part-account.md)), so lending late in a Period is not wasted.

## Consequences

- The Manager who gives up a week of an engineer's time gets GPU hours back for it, which is the proposal's central promise.
- Skill is not priced. A senior engineer and an intern earn the same Day Rate. Skill-based rates are named as Future; they need usage data and invite arguments about who is worth what.
- Because borrowing is free, demand for people is bounded only by availability and the lending Manager's judgement. The Shortlist ordering by fewest existing Loans spreads Loans across Members rather than exhausting the most popular ones.
- An Earning correction after an early end is the only Transaction that can take a Balance below zero; the affordability check then blocks new priced Requests until a Grant or Earning restores it.

## Alternatives rejected

- Charging for people: rebuilds hoarding.
- Charging the borrowing Team and paying the lending Team the same amount (a transfer): money semantics, and still a price on borrowing.
- Skill-based Day Rates in Release 1: no data to set them and a temptation to burn capacity to hit forecasts.

Related: `CONTEXT.md` (Matched, Person, Loan, Day Rate, Earning); SRS sections 4.5 and 4.10 and the business rules on Matched lines costing nothing and the Day Rate.
