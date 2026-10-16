# OpenBench web client

The React single-page application through which every role works (design Section 4, "Web client"). It talks to the server only through the REST API under `/api`, renders what the server computes, and holds no business rule: it never computes a Quote, a Balance, or a state.

Stack: React 19, TypeScript (strict), Vite, React Router, TanStack Query; Vitest and React Testing Library for tests. No component library and no global store.

## Run it

Node 24 is required; the repository's Nix dev shell provides it. Run these commands in `web/`.

| Task | Command | Notes |
| --- | --- | --- |
| Install | `npm ci` | Installs the exact versions in `package-lock.json`. |
| Develop | `npm run dev` | Serves the client on <http://localhost:5173> and forwards `/api` to the back end on <http://localhost:8080>, so start the back end first (`mvn spring-boot:run -Dspring-boot.run.profiles=demo` at the repository root). The demo logins are in the root README. |
| Test | `npm test` | Runs every test once. `npx vitest` watches instead. |
| Build | `npm run build` | Type-checks, then writes the production build to `web/dist`. The Maven build runs this and puts `web/dist` into the jar. |
| Preview | `npm run preview` | Serves `web/dist` on port 4173 with the same `/api` forwarding. |

`.npmrc` sets `save-exact`, so `npm install <package>` pins the exact version; keep it that way. `allowScripts` in `package.json` denies the install scripts of esbuild and fsevents, which ship prebuilt binaries and do not need them.

## Source layout

```text
src/
  main.tsx          entry point: creates the router and the query client
  App.tsx           the query cache around the router (main.tsx and the tests render it)
  routes.tsx        the route registry: lists every feature
  queryClient.ts    retry rules, and a 401 from any call signs the Member out
  styles.css        the one stylesheet; design tokens are CSS variables at the top
  api/              client.ts (the fetch wrapper, ApiError) and types.ts (the JSON types)
  auth/             the session: useMe, sign-in and sign-out, the /login page, roles
  shell/            the frame: header and menu, route guards, Not found, Not allowed, ErrorPage
  ui/               the house style's components and formatting helpers
  features/<name>/  one folder per screen or group of screens
  test/             test helpers: mockApi, renderApp, demo Members
```

Imports go downward only: `features` may use `shell`, `auth`, `ui`, and `api`; `shell` uses `auth`, `ui`, `api`; `auth` uses `ui` and `api`; `ui` uses `api` (for `ApiError`); `api` uses nothing.

## Add a screen

1. Make a folder `src/features/<name>/` with the pages and an `index.tsx` that exports a `Feature` (`src/shell/feature.ts`): its routes, each with the roles that may open it, and its menu items, each with the roles that see it. `MEMBER` means every signed-in Member.

   ```tsx
   export const requestsFeature: Feature = {
     routes: [
       { path: '/requests', element: <RequestsPage />, roles: ['MANAGER', 'PLANNER'] },
       { path: '/requests/:id', element: <RequestPage />, roles: ['MANAGER', 'PLANNER'] },
     ],
     nav: [
       {
         to: '/requests',
         label: 'Requests',
         description: 'Draft, submit, and follow your Team’s Requests.',
         roles: ['MANAGER', 'PLANNER'],
       },
     ],
   };
   ```

2. Add it to `features` in `src/routes.tsx`. The menu, the home page links, and the role check on each route follow from it. A page the roles do not allow shows "Not allowed"; this is a convenience, since the server checks the role on every call.

## Call the API

- Use `api.get/post/put/delete` from `src/api/client.ts`, never `fetch`. Paths are full (`/api/requests/42/submit`). The client sends the session cookie, copies the `XSRF-TOKEN` cookie into `X-XSRF-TOKEN` on every write, and throws an `ApiError` for any failed response: `status`, `code` (such as `INSUFFICIENT_BALANCE`), `title`, `detail` (show it as is), `errors` (per field, on 400), and `properties` (anything else, such as `earliestHour`). A server that cannot be reached gives status 0, code `NETWORK_ERROR`.
- Add the JSON types to `src/api/types.ts` under a heading for the API group. Use the shared types there: `Id`, `Credits`, `Instant`, `TimeWindow`, `DayRange`, `Page<T>`.
- Keep a feature's query keys and hooks in `src/features/<name>/queries.ts`. Keys are arrays that start with the API group's noun: `['requests']`, `['requests', id]`, `['requests', id, 'draft']`. A mutation invalidates the keys it changes in its `onSuccess`, so a submit refreshes the Request, the list, and the Account.
- Server state lives only in the query cache. Form state stays in its screen. Reads retry up to three times when the server is unreachable or answers 5xx; writes never retry. A 401 from any call returns the Member to `/login`.
- The signed-in Member is `useCurrentMember()` on any page; it never needs loading.

## Show things

- Start each page with `PageHeader`. While its data loads, show `Loading`; if the read fails, return `<ErrorPage error={query.error} />` from `src/shell/ErrorPage.tsx` (403 shows Not allowed, 404 Page not found, anything else the problem).
- Show a failed write next to the action with `<ProblemMessage error={mutation.error} />` (it renders nothing while there is no error), and the server's per-field messages with `<Field label="Units" error={fieldError(mutation.error, 'units')}>`.
- Lists use `Table` with `Column`s; mark credits and counts `numeric`. Use `EmptyState` when a list is empty and `StatusBadge` for upper-case states.
- Format with `formatCredits` (two decimals), `formatInstant` and `formatWindow` (company time zone, America/New_York, such as "Tue Oct 27, 14:00 to 16:00"), and `formatEnum`. Turn an `<input type="datetime-local">` value into an instant with `toInstant`, and back with `toPickerValue`.
- Use the CSS variables and classes in `styles.css` rather than new colors, sizes, or inline styles. Desktop browsers only.

## Test

Tests sit next to the code as `*.test.ts(x)`. They render the real application and mock only the network:

```tsx
const server = mockApi({
  'GET /api/me': session(alice),
  'GET /api/requests/:id': json(request),
  'POST /api/requests/:id/submit': problem(422, 'INSUFFICIENT_BALANCE', 'The Required lines need 1840.00 credits.'),
});
const { user } = renderApp('/requests/42');
await user.click(await screen.findByRole('button', { name: 'Submit' }));
expect(await screen.findByText('The Required lines need 1840.00 credits.')).toBeInTheDocument();
expect(server.callsTo('POST /api/requests/:id/submit')).toHaveLength(1);
```

- `mockApi(routes)` (`src/test/mockApi.ts`) replaces `fetch`; routes are `'METHOD /path'` with `:name` segments, and each reply is a `Response` or a function of the request. It records every call in `calls`; `callsTo(route)` filters them. A request no route matches gets a 404 problem that names it. `json`, `noContent`, `problem`, and `session` build replies; `session(me)` answers `GET /api/me` and sets the CSRF cookie as the server does.
- `renderApp(path, { features })` (`src/test/renderApp.tsx`) renders the application at a path with the real routes, guard, and query client, without read retries. Pass `features` to add stand-in features.
- `src/test/members.ts` has demo Members (`alice`, `ben`, `carol`, `dave`) as `/api/me` returns them.
- Query by role and accessible name (`getByRole('button', { name: 'Submit' })`, `getByLabelText('Username')`), as a user would.

## Gotchas

- Import everything from `react-router`, never `react-router/dom`: in Vitest the two load separate copies of the router, and the tests fail with "must be used within a data router".
- React 19 hoists a `<title>` rendered anywhere into the document head; `PageHeader` uses this to name the browser tab.
