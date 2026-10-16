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
