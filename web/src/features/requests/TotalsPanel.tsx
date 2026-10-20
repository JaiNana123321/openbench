import type { ReactNode } from 'react';
import type { DraftView } from '../../api/types';
import { formatCredits } from '../../ui';

interface TotalsPanelProps {
  view: DraftView;
  /** The Request's actions under the totals, such as the Submit button. */
  children?: ReactNode;
}

/**
 * The sums of the Required and the Optional lines' Quotes, the Team's Available Balance, and the
 * shortfall, drawn as the server computed them.
 */
export function TotalsPanel({ view, children }: TotalsPanelProps) {
  return (
    <aside className="panel totals" aria-labelledby="totals-title">
      <h2 id="totals-title" className="section-title">
        Totals
      </h2>
      <dl className="details">
        <dt>Required lines</dt>
        <dd className="numeric">{formatCredits(view.requiredSum)}</dd>
        <dt>Optional lines</dt>
        <dd className="numeric">{formatCredits(view.optionalSum)}</dd>
        <dt>Available Balance</dt>
        <dd className="numeric">{formatCredits(view.availableBalance)}</dd>
      </dl>
      {view.shortfall > 0 ? (
        <div className="shortfall" role="alert">
          <p className="shortfall__title">Short by {formatCredits(view.shortfall)} credits</p>
          <p className="shortfall__detail">
            The Required lines cost more than the Available Balance. Mark some Optional, split them, or remove
            them before submitting.
          </p>
        </div>
      ) : (
        <p className="field__hint">The Required lines fit the Available Balance.</p>
      )}
      {children && <div className="actions">{children}</div>}
    </aside>
  );
}
