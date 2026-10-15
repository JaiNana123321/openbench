# ADR-0003: Priority is bought with a premium, not granted by rank

Status: Accepted
Date: 2026-09-19
Deciders: Jai Nana, Brian Lujan, Fotih Turopov, Yerassyl Shnazbayev

## Context

The proposal promised priority bumping for Exclusive resources and the team kept it in Release 1. "Priority" could come from a per-Team rank set by the Capacity Planner, from a per-Request urgency chosen by the requester, or from both. It also had to be decided what happens to the line that is displaced.

## Decision

A Manager marks an Exclusive line as a **Priority line** and its Quote is multiplied by the **priority premium**, a multiple set by the Capacity Planner (default 2x). A Priority line can Bump a standard line and cannot itself be Bumped. Priority never bumps Priority; among Priority lines the earlier submission wins.

Rules for the Bumped side:

- The Bumped line goes to the head of the waitlist for its Resource Type and window, its Manager is notified, and its Charge is refunded in full. The Refund Schedule does not apply because the Team did nothing wrong.
- No Bump happens inside the **bump cutoff** before the window starts, set per Resource Type by the Capacity Planner (default 24 hours).
- If the Bumped line was Required in an Allocated Request, that Request returns to Waiting; its other lines stay allocated.

Priority lines are Exclusive-only in Release 1. Extending them to Metered pools is the Stretch item "service tiers and preemption".

## Consequences

- Priority is self-limiting: it spends scarce credits, so it is used for what matters.
- It links lending to priority. The Teams with the most earned credits are the ones lending people ([ADR-0002](0002-two-part-account.md)), so the ability to Bump is earned, not assigned.
- Dynamic pricing does not remove the temptation to gamble on a standard line for critical work; the disruption of being Bumped does, and that is by design. Penalising the Bumped Team was rejected.
- A Priority line inside the cutoff cannot Bump; it joins the waitlist ahead of all standard lines instead.

## Alternatives rejected

- Planner-set Team rank: hard-codes "the important Teams win", which the proposal criticises; named as Future.
- Requester-chosen urgency without a price: free to claim, so everyone claims it.
- Partial refund or penalty for the Bumped Team: punishes a Team that followed the rules.

Related: `CONTEXT.md` (Priority line, Bump, Waitlist, Unit Price, Price Band); SRS section 4.6 and business rules on the priority premium, bump cutoff, and Bumped lines.
