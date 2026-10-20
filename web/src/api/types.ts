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
}

/** The body of POST /api/requests/{id}/lines. `namedResourceId` is for an Exclusive line only. */
export interface NewLine {
  typeId: Id;
  quantity: number;
  start: Instant;
  end: Instant;
  namedResourceId?: Id;
}

/** The body of PUT /api/requests/{id}/lines/{lineId}: the line's ask, replaced whole; the type stays. */
export interface LineEdit {
  quantity: number;
  start: Instant;
  end: Instant;
  namedResourceId?: Id;
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
