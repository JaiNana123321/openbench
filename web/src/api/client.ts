import type { Problem } from './types';

// The one way the client calls the server (design Section 4, Web client, "State and data fetching").
// It sends the session cookie, copies the CSRF token into a header on every write, and turns
// every failed response into an ApiError. Screens never call fetch themselves.

/** The server sets this cookie on every response; script may read it. */
const CSRF_COOKIE = 'XSRF-TOKEN';
/** Every POST, PUT, and DELETE carries the cookie's value in this header. */
const CSRF_HEADER = 'X-XSRF-TOKEN';

/** Per-field messages of a 400, keyed by field name. */
export type FieldErrors = Record<string, string>;

/** Query-string values; `undefined` leaves the parameter out. */
export type QueryParams = Record<string, string | number | boolean | undefined>;

const PROBLEM_MEMBERS = new Set(['type', 'title', 'status', 'detail', 'instance', 'code', 'errors']);

/**
 * A failed API call. `detail` is the sentence to show the user; `code` is the stable string to
 * branch on (`INSUFFICIENT_BALANCE`, `BAD_CREDENTIALS`, ...). A server that could not be
 * reached gives status 0 and code `NETWORK_ERROR`.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string;
  /** Per-field messages; empty unless the server rejected fields (400). */
  readonly errors: FieldErrors;
  /** The problem's other properties, such as `earliestHour`. */
  readonly properties: Record<string, unknown>;

  constructor(problem: Problem, options?: ErrorOptions) {
    super(problem.detail, options);
    this.name = 'ApiError';
    this.status = problem.status;
    this.code = problem.code;
    this.title = problem.title;
    this.detail = problem.detail;
    this.errors = toFieldErrors(problem.errors);
    this.properties = Object.fromEntries(
      Object.entries(problem).filter(([name]) => !PROBLEM_MEMBERS.has(name)),
    );
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/** The server's message for one field of a rejected write, if any: `fieldError(save.error, 'units')`. */
export function fieldError(error: unknown, field: string): string | undefined {
  return isApiError(error) ? error.errors[field] : undefined;
}

export const api = {
  get: <T>(path: string, query?: QueryParams) => request<T>('GET', path, { query }),
  post: <T = void>(path: string, body?: unknown) => request<T>('POST', path, jsonBody(body)),
  put: <T = void>(path: string, body?: unknown) => request<T>('PUT', path, jsonBody(body)),
  delete: <T = void>(path: string) => request<T>('DELETE', path, {}),
  /** POST as an HTML form would (`application/x-www-form-urlencoded`), as the login expects. */
  postForm: <T = void>(path: string, fields: Record<string, string>) =>
    request<T>('POST', path, {
      body: new URLSearchParams(fields).toString(),
      contentType: 'application/x-www-form-urlencoded',
    }),
};

interface RequestOptions {
  query?: QueryParams;
  body?: string;
  contentType?: string;
}

async function request<T>(method: string, path: string, options: RequestOptions): Promise<T> {
  const headers = new Headers({ Accept: 'application/json, application/problem+json' });
  if (options.contentType) headers.set('Content-Type', options.contentType);
  if (method !== 'GET') {
    const token = readCookie(CSRF_COOKIE);
    if (token !== undefined) headers.set(CSRF_HEADER, token);
  }

  let response: Response;
  try {
    response = await fetch(withQuery(path, options.query), {
      method,
      headers,
      body: options.body,
      credentials: 'same-origin',
    });
  } catch (cause) {
    throw new ApiError(
      fallbackProblem(0, 'Server unreachable', path, 'NETWORK_ERROR',
        'Could not reach the server. Check your connection and try again.'),
      { cause },
    );
  }

  if (!response.ok) throw await toApiError(response, path);
  const text = await response.text();
  return (text === '' ? undefined : JSON.parse(text)) as T;
}

function jsonBody(body: unknown): RequestOptions {
  return body === undefined ? {} : { body: JSON.stringify(body), contentType: 'application/json' };
}

function withQuery(path: string, query: QueryParams | undefined): string {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query ?? {})) {
    if (value !== undefined) params.set(name, String(value));
  }
  const search = params.toString();
  return search === '' ? path : `${path}?${search}`;
}

export function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const entry = document.cookie.split('; ').find((cookie) => cookie.startsWith(prefix));
  return entry === undefined ? undefined : decodeURIComponent(entry.slice(prefix.length));
}

async function toApiError(response: Response, path: string): Promise<ApiError> {
  const fallback = fallbackProblem(response.status, response.statusText || 'Request failed', path);
  const contentType = response.headers.get('Content-Type') ?? '';
  if (contentType.includes('json')) {
    try {
      const body: unknown = await response.json();
      if (typeof body === 'object' && body !== null) return new ApiError({ ...fallback, ...body });
    } catch {
      // Not JSON after all: fall through to the generic error.
    }
  }
  return new ApiError(fallback);
}

/** The problem used when the server sent none, and to fill members a problem left out. */
function fallbackProblem(
  status: number,
  title: string,
  instance: string,
  code = `HTTP_${status}`,
  detail = fallbackDetail(status),
): Problem {
  return { type: 'about:blank', title, status, detail, instance, code };
}

function fallbackDetail(status: number): string {
  if (status === 401) return 'You are not signed in.';
  if (status === 403) return 'You are not allowed to do this.';
  if (status === 404) return 'This was not found.';
  if (status >= 500) return 'The server could not complete the request. Try again in a moment.';
  return `The request failed (status ${status}).`;
}

/** Accepts per-field errors as a map (`{"name": "must not be blank"}`) or a list of `{field, message}`. */
function toFieldErrors(errors: unknown): FieldErrors {
  const result: FieldErrors = {};
  if (Array.isArray(errors)) {
    for (const entry of errors) {
      if (typeof entry === 'object' && entry !== null && 'field' in entry) {
        const { field, message, detail } = entry as Record<string, unknown>;
        result[String(field)] = String(message ?? detail ?? '');
      }
    }
  } else if (typeof errors === 'object' && errors !== null) {
    for (const [field, message] of Object.entries(errors)) {
      result[field] = Array.isArray(message) ? message.join(' ') : String(message);
    }
  }
  return result;
}
