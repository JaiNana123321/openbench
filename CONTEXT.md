# OpenBench

An internal marketplace where a team's manager posts one request for people, exclusive resources, and metered capacity, and the system allocates each part under the policy that fits its allocation class, settled in credits.

Naming rule: prefer the everyday word a Manager would use. Credit is the unit and never a transaction direction. No money words (bank, funds, pay, debit), because credits are showback, not billing.

## Language

### People and roles

**Member**:
Any user of the system. Belongs to exactly one Team, has a skill profile and availability, and is lendable as a Person.
_Avoid_: user, employee, staff

**Team**:
The organizational unit that owns an Account and every Request made on its behalf. Has exactly one Manager.
_Avoid_: group, department, org

**Manager**:
The Member who leads a Team. The only actor who submits Requests, and the approver for lending that Team's Members.
_Avoid_: team lead, owner, approver

**Resource Owner**:
A Member responsible for the Inventory records of specific Exclusive or Metered resources. Never approves allocations.
_Avoid_: admin, custodian

**Capacity Planner**:
The role that sets each Team's Grant per Period, Unit Prices, the Day Rate, and Refund Schedules, and receives escalations.
_Avoid_: planner, finance, admin

**Administrator**:
The role that manages users, Teams, and Resource Type definitions. Configures the system; sets no policy.
_Avoid_: superuser, root

### Resources

**Resource**:
Anything that can be allocated: a Person, an Exclusive resource such as a room, or a Metered pool such as GPU hours. Every Resource has exactly one Resource Type.
_Avoid_: asset, item, capacity (as a noun for a thing)

**Resource Type**:
A named definition, created as data by an Administrator, that fixes a Resource's Allocation Class and attributes.
_Avoid_: descriptor, category, kind

**Allocation Class**:
One of three policies a Resource Type declares, deciding how Requests for it are allocated: Matched, Exclusive, or Metered.
_Avoid_: resource class, policy type

**Matched**:
The Allocation Class for People. Allocated by matching skills and availability, gated by the Person's Manager.
_Avoid_: matched agents, agents, talent

**Person**:
A Member viewed as a Matched Resource: someone with skills and availability who can be lent to another Team.
_Avoid_: agent, worker, headcount

**Exclusive**:
The Allocation Class for Resources held whole over a time window, such as rooms or instruments. Allocated with conflict detection, a waitlist, and priority bumping.
_Avoid_: reservable, bookable

**Metered**:
The Allocation Class for divisible Resources drawn against a Team quota, such as compute hours or license seats.
_Avoid_: pooled, consumable, quota resource

**Pool Capacity**:
The total units a Metered Resource Type can supply at once, such as 32 GPUs, maintained by its Resource Owner. Allocations in any hour may not exceed it.
_Avoid_: quota, limit, size

**Quota**:
An optional per-Team cap on the units of one Metered Resource Type the Team may hold concurrently, set by the Capacity Planner. A safety valve against monopolizing a pool, not a rationing mechanism.
_Avoid_: budget, allowance, share, capacity

**Inventory**:
The record of every Resource that exists, its Resource Type, and who currently holds it or has it on Hold.
_Avoid_: capacity ledger, catalog, registry

### Requests

**Request**:
A single request, owned by a Team and submitted by its Manager, containing one or more Request Lines that may span all three Allocation Classes.
_Avoid_: composite request, order, booking, ticket, bundle

**Request Line**:
One need inside a Request: a Resource Type, a quantity, and a time window. Every line has exactly one Allocation Class, taken from its Resource Type.
_Avoid_: item, line item, sub-request

**Suggestion**:
A single Request Line a Member proposes to their own Manager. Has no allocation effect until the Manager accepts it into a Request; several Suggestions may be accepted into the same line.
_Avoid_: draft, proposal, wish

**Required line** / **Optional line**:
A Manager's marking on each Request Line. A Request is fulfilled only when every Required line is allocated; Optional lines allocate if they can and never block the outcome.
_Avoid_: mandatory, nice-to-have, hard/soft

**Hold**:
A tentative, uncharged allocation of an Exclusive or Metered line placed while a Request's Matched lines await approval. Commits when the Request is fulfilled, releases when it fails or the Objection Window lapses.
_Avoid_: reservation, soft booking, lock

**Objection Window**:
The bounded period a lending Manager has to approve or refuse a Matched line before the Request escalates to the Capacity Planner. Also the lifetime of any Hold on that Request.
_Avoid_: SLA, approval deadline, timeout

**Candidate**:
A Person who satisfies a Matched line's skills and availability, has no Loan overlapping the line's window, and appears on its Shortlist. Becomes a Loan once their Manager approves.
_Avoid_: match, applicant, option

**Shortlist**:
The list of Candidates the system produces for a skill-based Matched line, ordered by fewest person-days lent in the current Period, from which the requesting Manager selects.
_Avoid_: search results, pool, matches

**Waitlist**:
The ordered list of Required lines that could not be satisfied for a Resource Type and window. Priority lines first, then by submission time; a Bumped line goes to the head. Optional lines are never waitlisted.
_Avoid_: queue, backlog, pending list

**Loan**:
A Person allocated to a Team other than their own for a time window. The fulfilled form of a Matched line. A Person is in at most one Loan at a time.
_Avoid_: assignment, secondment, transfer, placement

**Priority line**:
An Exclusive Request Line that is charged a premium, a multiple of the Unit Price set by the Capacity Planner. Can Bump a standard line and cannot itself be Bumped.
_Avoid_: urgent, high-priority flag, VIP

**Bump**:
The displacement of a standard Exclusive line by a Priority line. The displaced line goes to the head of the waitlist with a full Refund. Not allowed inside the bump cutoff before the window starts.
_Avoid_: preempt, evict, override

**Release**:
A Manager giving back an allocated Exclusive or Metered line, in whole or in part, before it is consumed, or ending a Loan early. Triggers a Refund according to the Refund Schedule.
_Avoid_: cancel, return, un-book

### Credits

**Credit**:
The unit of account in the Ledger. No real money; used for showback, not billing.
_Avoid_: point, token, dollar, funds, money

**Ledger**:
The record of every Team's Account and every Transaction. Holds no capacity information.
_Avoid_: credit ledger, capacity ledger, bank, wallet

**Account**:
A Team's standing in the Ledger: its granted credits, its earned credits, and its held credits.
_Avoid_: wallet, budget (as the thing itself), balance sheet

**Balance**:
A Team's granted credits plus earned credits.
_Avoid_: funds, total

**Available Balance**:
Balance minus held credits. What a Team can commit to a new Request.
_Avoid_: free balance, spendable

**Held credits**:
Credits set aside for a Request that is Pending, equal to what its Holds will cost when they commit.
_Avoid_: encumbrance, reserved credits, pending charges

**Transaction**:
One entry in the Ledger against an Account. Exactly four kinds: a Grant (from the Capacity Planner), a Charge (for an allocated Exclusive or Metered line), an Earning (for a Loan), or a Refund (for a Release).
_Avoid_: debit, credit (as a direction), payment, entry

**Grant**:
Credits given to a Team by the Capacity Planner at the start of a Period, or as a mid-period adjustment. Expire at the end of the Period.
_Avoid_: allowance, budget allocation, deposit

**Period**:
The fixed calendar interval over which Grants are issued and expire. Set by the Capacity Planner.
_Avoid_: cycle, quarter, budget period

**Statement**:
A Team's per-Period view of its Account: Grants, Charges, Earnings, Refunds, and what expired. The showback artifact.
_Avoid_: report, bill, invoice

**Unit Price**:
The base credits per unit of an Exclusive or Metered Resource Type (per hour of a room, per GPU hour), set by the Capacity Planner, before any Price Band multiplier. Matched lines have no Unit Price.
_Avoid_: cost, rate, fee, base price

**Price Band**:
A time-of-day and day-of-week window, defined per Resource Type by the Capacity Planner, carrying a multiplier on the Unit Price. Rewards use at low-demand times.
_Avoid_: tariff, peak/off-peak (as terms), surge

**Quote**:
The credits a Request Line will cost, shown to the Manager while drafting and locked when the Request is submitted. The Charge that posts equals the Quote.
_Avoid_: estimate, price tag, invoice

**Day Rate**:
The flat credits a Team earns per person-day of a Loan, set once by the Capacity Planner. The same for every Person regardless of skill.
_Avoid_: lending rate, headcount rate, salary, chargeback

**Refund Schedule**:
The rule, set per Resource Type by the Capacity Planner, giving the fraction of a Charge returned on Release as a step function of how far ahead of the window start the Release happens. Never reaches 100%.
_Avoid_: cancellation policy, penalty
