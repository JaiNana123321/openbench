// The JSON the REST API sends and receives (design Section 7, "Representation").
// Hand-written; add the types of a new API group under its own heading below.

// ---------------------------------------------------------------- Shared values

/** Ids are JSON numbers. */
export type Id = number;

/** Credits are JSON numbers with two decimals, computed by the server. Never price in the client. */
export type Credits = number;

/** An ISO-8601 date-time with an offset, such as "2026-10-27T14:00:00-04:00". */
export type Instant = string;

/** An ISO-8601 date, such as "2026-10-27". */
export type LocalDate = string;

/** A half-open time window: `end` is excluded. */
export interface TimeWindow {
  start: Instant;
  end: Instant;
}

/** An inclusive range of days. */
export interface DayRange {
  from: LocalDate;
  to: LocalDate;
}

/** One page of a paged list (query parameters `page`, from 0, and `size`). */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

/**
 * An RFC 9457 problem detail, the body of every error response (`application/problem+json`).
 * `detail` is one sentence to show the user as is; `code` is stable and safe to branch on.
 */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  code: string;
  /** Per-field messages, on 400 only. Read them through `ApiError.errors`. */
  errors?: unknown;
  /** Other properties of a particular refusal, such as `earliestHour`. */
  [property: string]: unknown;
}

// ---------------------------------------------------------------- Session and Members

/** Every signed-in Member holds MEMBER; the other roles add to it. */
export type Role = 'MEMBER' | 'MANAGER' | 'RESOURCE_OWNER' | 'PLANNER' | 'ADMIN';

export interface TeamRef {
  id: Id;
  name: string;
}

/** GET /api/me: the signed-in Member. */
export interface Me {
  id: Id;
  name: string;
  username: string;
  team: TeamRef;
  roles: Role[];
}

// ---------------------------------------------------------------- Inventory

/** The policy a Resource Type declares, deciding how Request Lines for it are allocated. */
export type AllocationClass = 'MATCHED' | 'EXCLUSIVE' | 'METERED';

/** What values an attribute takes; a LIST attribute takes one of its `allowedValues`. */
export type AttributeKind = 'TEXT' | 'NUMBER' | 'DATE' | 'LIST';

/** An attribute of a Resource Type. `allowedValues` is set for LIST and null for every other kind. */
export interface Attribute {
  id: Id;
  name: string;
  kind: AttributeKind;
  allowedValues: string[] | null;
}

/** GET /api/inventory/types and /types/{id}. `poolCapacity` is set for Metered types only. */
export interface ResourceType {
  id: Id;
  name: string;
  allocationClass: AllocationClass;
  attributes: Attribute[];
  retired: boolean;
  poolCapacity: number | null;
}

/**
 * The body of POST /api/inventory/types and PUT /types/{id}: the whole definition. An attribute
 * without `id` is new; an existing attribute left out is removed.
 */
export interface ResourceTypeDefinition {
  name: string;
  allocationClass: AllocationClass;
  attributes: (Omit<Attribute, 'id'> & { id?: Id })[];
}

/** GET /api/inventory/resources (paged) and /resources/{id}. `attributes` maps names to values. */
export interface Resource {
  id: Id;
  typeId: Id;
  typeName: string;
  name: string;
  ownerId: Id;
  ownerName: string;
  units: number;
  attributes: Record<string, string>;
  retired: boolean;
}

/** The body of POST /api/inventory/resources. `units` is required for a Metered type only. */
export interface NewResource {
  typeId: Id;
  name: string;
  units?: number;
  attributes: Record<string, string>;
}

/** The body of PUT /api/inventory/resources/{id}: an attribute left out or blank loses its value. */
export interface ResourceEdit {
  name: string;
  attributes: Record<string, string>;
  ownerId?: Id;
}

// ---------------------------------------------------------------- Settings and Ledger

/** GET /api/settings/periods: a Period, inclusive at both ends; `previous` is the one before it. */
export interface Period {
  id: Id;
  startsOn: LocalDate;
  endsOn: LocalDate;
  previous: Id | null;
}

/** GET /api/ledger/account: a Team's Account. Balance is granted plus earned; available subtracts held. */
export interface Account {
  team: Id;
  granted: Credits;
  earned: Credits;
  held: Credits;
  balance: Credits;
  available: Credits;
}

export type TransactionKind = 'GRANT' | 'CHARGE' | 'EARNING' | 'REFUND';

/** One Transaction on a Statement. A null `actor` is the system; `line` and `reason` may be null. */
export interface LedgerTransaction {
  id: Id;
  kind: TransactionKind;
  amount: Credits;
  grantedPart: Credits;
  earnedPart: Credits;
  postedAt: Instant;
  line: Id | null;
  actor: Id | null;
  actorName: string | null;
  reason: string | null;
}

/** Granted and earned credits at an edge of a Period. */
export interface CreditFigures {
  granted: Credits;
  earned: Credits;
}

/** GET /api/ledger/statements/{teamId}/{periodId}: a Team's Statement for a Period. */
export interface Statement {
  team: TeamRef;
  period: Period;
  opening: CreditFigures;
  totals: {
    grants: Credits;
    charges: Credits;
    earnings: Credits;
    refunds: Credits;
    /** Granted credits that expired when the Period closed; zero while it is open. */
    expired: Credits;
  };
  closing: CreditFigures;
  /** The Account as it stands now, whatever the Period. */
  account: Account;
  transactions: LedgerTransaction[];
}

// ---------------------------------------------------------------- Requests

export type RequestState = 'DRAFT' | 'SUBMITTED' | 'PENDING' | 'WAITING' | 'ALLOCATED' | 'WITHDRAWN' | 'REFUSED';

export type RequestLineState =
  | 'DRAFT'
  | 'EVALUATED'
  | 'HELD'
  | 'ALLOCATED'
  | 'WAITING'
  | 'RELEASED'
  | 'PARTLY_RELEASED'
  | 'BUMPED'
  | 'AWAITING_APPROVAL'
  | 'APPROVED'
  | 'LOANED'
  | 'REFUSED'
  | 'DROPPED'
  | 'CANCELED';

/** POST /api/requests, and the `request` of a Draft view: a Request without its lines. */
export interface RequestRecord {
  id: Id;
  team: TeamRef;
  state: RequestState;
  createdAt: Instant;
  submittedAt: Instant | null;
}

/**
 * A Request Line. `quantity` counts instances of an Exclusive type and units of a Metered type.
 * `quote` is null when the type has no prices; a Draft's Quotes are current, a submitted line's locked.
 */
export interface RequestLine {
  id: Id;
  requestId: Id;
  typeId: Id;
  typeName: string;
  allocationClass: AllocationClass;
  quantity: number;
  window: TimeWindow;
  namedResource: Id | null;
  namedResourceName: string | null;
  required: boolean;
  state: RequestLineState;
  quote: Credits | null;
  /** Null unless an Exclusive line recurs weekly; `window` is then the first occurrence and the Quote covers all. */
  recurrence: WeeklyRecurrence | null;
  /** The Priority mark, offered on Exclusive lines only; the Quote then carries the priority premium. */
  priority: boolean;
}

/** The body of POST /api/requests/{id}/lines. `namedResourceId` is for an Exclusive line only. */
export interface NewLine {
  typeId: Id;
  quantity: number;
  start: Instant;
  end: Instant;
  namedResourceId?: Id;
  /** An Exclusive line only; absent for a single occurrence. */
  recurrence?: WeeklyRecurrence;
}

/** The body of PUT /api/requests/{id}/lines/{lineId}: the line's ask, replaced whole; the type stays. */
export interface LineEdit {
  quantity: number;
  start: Instant;
  end: Instant;
  namedResourceId?: Id;
  /** An Exclusive line only; absent for a single occurrence. */
  recurrence?: WeeklyRecurrence;
}

/**
 * GET /api/requests/{id}/draft: the lines with their Quotes and the server's totals. `shortfall` is
 * the Required sum above the Available Balance, 0 when it fits.
 */
export interface DraftView {
  request: RequestRecord;
  lines: RequestLine[];
  requiredSum: Credits;
  optionalSum: Credits;
  availableBalance: Credits;
  shortfall: Credits;
}

/** One row of GET /api/requests (paged). */
export interface RequestSummary {
  id: Id;
  team: TeamRef;
  state: RequestState;
  createdAt: Instant;
  submittedAt: Instant | null;
  lineCount: number;
  requiredSum: Credits;
}

// ---------------------------------------------------------------- Requests: submission and detail

/** A Resource an allocated Exclusive line is on. */
export interface AssignedResource {
  id: Id;
  name: string;
}

/**
 * One line of a Request detail. `reason` is a code such as `NO_FREE_INSTANCE`, `POOL_FULL`, or
 * `QUOTA_EXCEEDED`, with `reasonMessage` the server's sentence, for a line that waits or was
 * Dropped; `waitlistPosition` counts from 1 while the line is on its waitlist.
 */
export interface LineDetail {
  line: RequestLine;
  assignedResources: AssignedResource[];
  reason: string | null;
  reasonMessage: string | null;
  waitlistPosition: number | null;
  /** What an Exclusive or Metered line holds now, one part per occurrence; empty when nothing is allocated. */
  allocatedParts: AllocatedPart[];
}

/**
 * One entry of a Request's history: a change of the Request (`line` null) or of one line.
 * `fromState` is null when the Request was created; `actor` is null for the system.
 */
export interface RequestEvent {
  at: Instant;
  line: Id | null;
  fromState: string | null;
  toState: string;
  actor: Id | null;
  actorName: string | null;
  note: string | null;
}

/**
 * GET /api/requests/{id}: the Request, its lines as they stand, and its history, oldest first.
 * POST /api/requests/{id}/submit and /withdraw answer the Request in its new state (`RequestRecord`).
 */
export interface RequestDetail {
  request: RequestRecord;
  lines: LineDetail[];
  history: RequestEvent[];
}

// ---------------------------------------------------------------- Profiles and the skill list

/** GET /api/skills: an entry of the Administrator's skill list. A retired skill is no longer offered. */
export interface Skill {
  id: Id;
  name: string;
  retired: boolean;
}

/**
 * GET /api/members/{id}/profile (`me` for the caller): name, email, Team, skills, and availability,
 * and no other personal data. The Team is read-only; only an Administrator moves a Member.
 */
export interface MemberProfile {
  id: Id;
  name: string;
  email: string;
  team: TeamRef;
  skills: Skill[];
  availability: DayRange[];
}

/** One row of GET /api/teams/{id}/members (paged): a Member of the Team. */
export interface MemberSummary {
  id: Id;
  name: string;
  username: string;
  team: TeamRef;
  active: boolean;
  roles: Role[];
}

// ---------------------------------------------------------------- Notifications

/** What a notification is about. */
export type NotificationKind =
  | 'APPROVAL_REQUESTED'
  | 'CANDIDATE_SELECTED'
  | 'LOAN_FORMED'
  | 'REQUEST_UPDATE'
  | 'BUMPED'
  | 'WAITLIST_FILLED'
  | 'ESCALATED'
  | 'SUGGESTION'
  | 'PERIOD_ROLLOVER';

/**
 * GET /api/notifications (paged: the caller's unread notices, newest first) and
 * POST /api/notifications/{id}/read. `link` is a client route such as "/requests/42", or null.
 */
export interface MemberNotification {
  id: Id;
  recipient: Id;
  kind: NotificationKind;
  text: string;
  link: string | null;
  createdAt: Instant;
  readAt: Instant | null;
}

// ---------------------------------------------------------------- Requests: Release and Refund

/** One occurrence of a line as allocated now: its window, shortened by an earlier end, and its instances or units. */
export interface AllocatedPart {
  window: TimeWindow;
  amount: number;
}

/** What a Release gives back: the whole line, an earlier end, some occurrences, or (Metered) fewer units. */
export type ReleaseKind = 'WHOLE' | 'EARLIER_END' | 'OCCURRENCES' | 'FEWER_UNITS';

/**
 * The body of POST /api/requests/{id}/lines/{lineId}/release, and the query of GET
 * .../release-preview. `newEnd` is for EARLIER_END, `occurrences` (their starts) for OCCURRENCES,
 * and `newUnits`, the units kept, for FEWER_UNITS.
 */
export interface ReleaseBody {
  kind: ReleaseKind;
  newEnd?: Instant;
  occurrences?: Instant[];
  newUnits?: number;
}

/**
 * One released piece of a Refund: the hours `released` of the occurrence `occurrence`, for `amount`
 * instances or units; `fraction` (such as 0.75) of its `attributable` share of the locked Quote,
 * by the lead time to the occurrence's start (`leadHours`, negative once it started).
 */
export interface RefundPiece {
  occurrence: TimeWindow;
  released: TimeWindow;
  amount: number;
  leadHours: number;
  fraction: number;
  attributable: Credits;
  refund: Credits;
}

/** GET .../release-preview: the Refund a Release would post now, and the pieces it sums. */
export interface RefundQuote {
  attributable: Credits;
  refund: Credits;
  pieces: RefundPiece[];
}

/** POST .../release: the line in its new state (RELEASED or PARTLY_RELEASED) and the Refund posted. */
export interface ReleaseOutcome {
  line: RequestLine;
  refund: RefundQuote;
}

// ---------------------------------------------------------------- Requests: weekly recurrence

/** An Exclusive line's window repeated on the same hours every week, `occurrences` weeks in all (2 to 52). */
export interface WeeklyRecurrence {
  occurrences: number;
}

// ---------------------------------------------------------------- Matched: Person lines, Shortlists, approvals
//
// The fields below extend interfaces declared above (TypeScript merges the declarations). They are
// optional so that Exclusive and Metered lines, and fixtures written before Person lines, need not
// name them; the server always sends them.

export interface RequestRecord {
  /** The end of the Objection Window, set at submission when a line needs approval and never changed. */
  windowEndsAt?: Instant | null;
}

export interface RequestLine {
  /** The one Person a Person line names (quantity 1), or null. */
  namedMember?: Id | null;
  namedMemberName?: string | null;
  /** The skills a skill-based Person line asks for; empty on any other line. */
  skills?: Skill[];
  /** A Person line's whole days, inclusive; null on other lines. */
  days?: DayRange | null;
}

/**
 * A Person line in POST /api/requests/{id}/lines and PUT .../lines/{lineId}: `start` is midnight of
 * the first day and `end` midnight after the last, company time; it names one Person in
 * `namedMemberId` (quantity 1) or asks for `skills`, a list of skill ids.
 */
export interface NewLine {
  namedMemberId?: Id;
  skills?: Id[];
}

export interface LineEdit {
  namedMemberId?: Id;
  skills?: Id[];
}

export type ApprovalState = 'OUTSTANDING' | 'APPROVED' | 'REFUSED' | 'CANCELED';

export type Decision = 'APPROVE' | 'REFUSE';

/** A Candidate selected for a Person line, with the state of their approval. */
export interface CandidateApproval {
  approval: Id;
  member: Id;
  name: string;
  team: TeamRef;
  state: ApprovalState;
  escalated: boolean;
  decidedByName: string | null;
  decidedAt: Instant | null;
  reason: string | null;
}

export interface LineDetail {
  /** A Person line's selected Candidates, in the order selected; empty on other lines. */
  candidates?: CandidateApproval[];
}

/** One row of a Shortlist: only what a Manager needs to choose. `position` counts from 1. */
export interface ShortlistEntry {
  position: number;
  member: Id;
  name: string;
  team: TeamRef;
  skills: string[];
  personDaysLent: number;
}

/** GET /api/requests/{id}/lines/{lineId}/shortlist: by person-days lent this Period, then name. */
export interface Shortlist {
  lineId: Id;
  candidates: ShortlistEntry[];
}

/**
 * GET /api/approvals and POST /api/approvals/{id}/decision: an approval as its lending Manager sees
 * it, with the Earning the lending Team would get and the end of the Objection Window.
 */
export interface ApprovalView {
  id: Id;
  requestId: Id;
  lineId: Id;
  candidate: Id;
  candidateName: string;
  requestingTeam: TeamRef;
  days: DayRange;
  earning: Credits;
  windowEndsAt: Instant | null;
  state: ApprovalState;
  escalated: boolean;
  decidedByName: string | null;
  decidedAt: Instant | null;
  reason: string | null;
}
