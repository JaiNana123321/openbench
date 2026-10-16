import { isApiError } from '../api/client';

interface ProblemMessageProps {
  /** Any error, usually a query's or mutation's `error`. Renders nothing when there is none. */
  error: unknown;
  /** Replaces the problem's own title, such as "Sign-in failed". */
  title?: string;
}

/** Shows a failed call inline: a title and the problem's `detail` sentence as the server wrote it. */
export function ProblemMessage({ error, title }: ProblemMessageProps) {
  if (!error) return null;
  const problem = isApiError(error)
    ? { title: error.title, detail: error.detail }
    : { title: 'Something went wrong', detail: 'An unexpected error occurred. Reload the page and try again.' };
  return (
    <div className="problem" role="alert">
      <p className="problem__title">{title ?? problem.title}</p>
      <p className="problem__detail">{problem.detail}</p>
    </div>
  );
}
