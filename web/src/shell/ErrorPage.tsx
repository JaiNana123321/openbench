import { useRouteError } from 'react-router';
import { isApiError } from '../api/client';
import { PageHeader, ProblemMessage } from '../ui';
import { NotAllowedPage } from './NotAllowedPage';
import { NotFoundPage } from './NotFoundPage';

/**
 * What a page shows when the data it needs fails to load:
 * `if (query.isError) return <ErrorPage error={query.error} />`.
 * A 403 shows "Not allowed", a 404 "Page not found", anything else the problem's detail.
 */
export function ErrorPage({ error }: { error: unknown }) {
  if (isApiError(error) && error.status === 403) return <NotAllowedPage />;
  if (isApiError(error) && error.status === 404) return <NotFoundPage />;
  return (
    <>
      <PageHeader title="Something went wrong" />
      <ProblemMessage error={error} title="The page could not be shown" />
    </>
  );
}

/** The router's error element: an error thrown while rendering a page. */
export function RouteErrorPage() {
  return <ErrorPage error={useRouteError()} />;
}
