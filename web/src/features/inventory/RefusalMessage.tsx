import { isApiError } from '../../api/client';
import { formatInstant, ProblemMessage } from '../../ui';

/**
 * A refused write: the problem's detail, and for UNITS_BELOW_USAGE and RESOURCE_IN_USE the
 * earliest hour at which the capacity is still needed.
 */
export function RefusalMessage({ error }: { error: unknown }) {
  const earliestHour = isApiError(error) ? error.properties.earliestHour : undefined;
  return (
    <>
      <ProblemMessage error={error} />
      {typeof earliestHour === 'string' && (
        <p className="field__hint">Earliest blocking hour: {formatInstant(earliestHour)}</p>
      )}
    </>
  );
}
