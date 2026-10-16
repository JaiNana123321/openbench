import type { ReactNode } from 'react';

interface EmptyStateProps {
  title: string;
  /** What to do about it, such as a link to create the first one. */
  children?: ReactNode;
}

/** What a list or page shows when there is nothing in it yet. */
export function EmptyState({ title, children }: EmptyStateProps) {
  return (
    <div className="empty">
      <p className="empty__title">{title}</p>
      {children && <div className="empty__body">{children}</div>}
    </div>
  );
}
