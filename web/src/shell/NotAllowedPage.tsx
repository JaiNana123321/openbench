import { Link } from 'react-router';
import { EmptyState, PageHeader } from '../ui';

/** Shown for a page the Member's roles do not allow, and for a 403 from the server. */
export function NotAllowedPage() {
  return (
    <>
      <PageHeader title="Not allowed" />
      <EmptyState title="Your roles do not allow this page.">
        <p>
          An Administrator can grant you a role if you need it. <Link to="/">Go to the home page</Link>
        </p>
      </EmptyState>
    </>
  );
}
