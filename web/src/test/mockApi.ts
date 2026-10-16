import { vi } from 'vitest';
import type { Me } from '../api/types';

// Tests mock the API at the fetch level: the real client, query hooks, and screens run, and only
// the network is replaced. Routes are 'METHOD /path' with optional :name segments.

export interface MockRequest {
  method: string;
  /** The path without the query string, such as /api/requests/7. */
  path: string;
  query: URLSearchParams;
  headers: Headers;
  body: string | undefined;
  /** The values of the route's :name segments. */
  params: Record<string, string>;
}

export type MockReply = Response | ((request: MockRequest) => Response | Promise<Response>);

/**
 * Replaces fetch with the given routes, for example
 * `mockApi({ 'GET /api/me': session(alice), 'POST /api/requests/:id/submit': json(request) })`.
 * Every call is recorded in `calls`. A request no route matches gets a 404 problem naming it.
 */
export function mockApi(routes: Record<string, MockReply>) {
  const table = Object.entries(routes).map(([route, reply]) => ({ ...compile(route), reply }));
  const calls: MockRequest[] = [];

  vi.stubGlobal('fetch', async (input: string, init: RequestInit = {}) => {
    const url = new URL(input, window.location.origin);
    const method = init.method ?? 'GET';
    const request: MockRequest = {
      method,
      path: url.pathname,
      query: url.searchParams,
      headers: new Headers(init.headers),
      body: typeof init.body === 'string' ? init.body : undefined,
      params: {},
    };
    calls.push(request);
    for (const route of table) {
      const params = route.match(method, url.pathname);
      if (params) {
        request.params = params;
        return typeof route.reply === 'function' ? route.reply(request) : route.reply.clone();
      }
    }
    return problem(404, 'NOT_FOUND', `No mock for ${method} ${url.pathname}.`);
  });

  return {
    calls,
    /** The recorded calls to one route, such as `callsTo('POST /api/login')`. */
    callsTo(route: string) {
      const { match } = compile(route);
      return calls.filter((call) => match(call.method, call.path));
    },
  };
}

function compile(route: string) {
  const [method, path] = route.split(' ');
  const names: string[] = [];
  const pattern = new RegExp(
    `^${path.replace(/:(\w+)/g, (_, name: string) => {
      names.push(name);
      return '([^/]+)';
    })}$`,
  );
  return {
    match(requestMethod: string, requestPath: string): Record<string, string> | undefined {
      const found = requestMethod === method ? pattern.exec(requestPath) : null;
      return found ? Object.fromEntries(names.map((name, i) => [name, found[i + 1]])) : undefined;
    },
  };
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export function noContent(): Response {
  return new Response(null, { status: 204 });
}

/** An RFC 9457 problem as the server sends it. */
export function problem(
  status: number,
  code: string,
  detail: string,
  properties: Record<string, unknown> = {},
): Response {
  const body = { type: 'about:blank', title: code, status, detail, instance: '/api', code, ...properties };
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

/** Sets a cookie as the server's Set-Cookie would; the setup file clears cookies after each test. */
export function setCookie(name: string, value: string): void {
  document.cookie = `${name}=${encodeURIComponent(value)}; path=/`;
}

/**
 * The reply of GET /api/me: the Member when signed in, 401 when signed out. Like the server,
 * it sets the XSRF-TOKEN cookie either way.
 */
export function session(me: Me | null, csrfToken = 'csrf-token-1'): () => Response {
  return () => {
    setCookie('XSRF-TOKEN', csrfToken);
    return me ? json(me) : problem(401, 'UNAUTHENTICATED', 'Sign in to continue.');
  };
}
