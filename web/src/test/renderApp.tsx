import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { App } from '../App';
import { createQueryClient } from '../queryClient';
import { buildRoutes, features as appFeatures } from '../routes';
import type { Feature } from '../shell/feature';

interface RenderAppOptions {
  /** The features to register; the application's own by default. */
  features?: readonly Feature[];
}

/**
 * Renders the whole application at a path, with the real routes, shell, guard, and query client,
 * against whatever mockApi serves. Reads do not retry, so a failing call fails the test at once.
 */
export function renderApp(path: string, { features = appFeatures }: RenderAppOptions = {}) {
  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({
    ...queryClient.getDefaultOptions(),
    queries: { ...queryClient.getDefaultOptions().queries, retry: false },
  });
  const router = createMemoryRouter(buildRoutes(features), { initialEntries: [path] });
  const user = userEvent.setup();
  const view = render(<App router={router} queryClient={queryClient} />);
  return { ...view, user, router, queryClient };
}
