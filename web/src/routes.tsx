import type { RouteObject } from 'react-router';
import { LoginPage } from './auth/LoginPage';
import { homeFeature } from './features/home';
import { AppShell } from './shell/AppShell';
import { RouteErrorPage } from './shell/ErrorPage';
import type { Feature } from './shell/feature';
import { NotFoundPage } from './shell/NotFoundPage';
import { RequireAuth } from './shell/RequireAuth';
import { RequireRole } from './shell/RequireRole';

/**
 * The route registry: every feature of the client, in menu order. To add a screen, add a folder
 * under src/features/ that exports a Feature, and list it here.
 */
export const features: Feature[] = [homeFeature];

/** /login is open to all; every other page needs a signed-in Member and sits inside the shell. */
export function buildRoutes(features: readonly Feature[]): RouteObject[] {
  const pages = features.flatMap((feature) => feature.routes);
  const nav = features.flatMap((feature) => feature.nav);
  return [
    {
      errorElement: (
        <main className="app-main">
          <RouteErrorPage />
        </main>
      ),
      children: [
        { path: '/login', element: <LoginPage /> },
        {
          element: (
            <RequireAuth>
              <AppShell nav={nav} />
            </RequireAuth>
          ),
          children: [
            {
              // A page that fails to render shows its error inside the shell.
              errorElement: <RouteErrorPage />,
              children: [
                ...pages.map(({ path, element, roles }) => ({
                  path,
                  element: <RequireRole roles={roles}>{element}</RequireRole>,
                })),
                { path: '*', element: <NotFoundPage /> },
              ],
            },
          ],
        },
      ],
    },
  ];
}

export const routes = buildRoutes(features);
