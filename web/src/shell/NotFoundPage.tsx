import { Link } from 'react-router';
import { EmptyState, PageHeader } from '../ui';

/** Shown for an unknown address, and for a 404 from the server. */
export function NotFoundPage() {
  return (
    <>
      <PageHeader title="Page not found" />
      <EmptyState title="There is nothing at this address.">
        <p>
          Check the address, or <Link to="/">go to the home page</Link>.
        </p>
      </EmptyState>
    </>
  );
}
