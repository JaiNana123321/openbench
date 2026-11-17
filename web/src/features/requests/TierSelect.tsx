import type { Id, RequestLine } from '../../api/types';
import { ProblemMessage } from '../../ui';
import { useEditLine, useTypeTiers } from './queries';

interface TierSelectProps {
  requestId: Id;
  line: RequestLine;
  /** The line's number in the table, for the select's label. */
  number: number;
}

/**
 * The service tier of a Metered Draft line (SRS Metered.Tier.Define): None, then each tier of the
 * type with its premium. A choice saves the line with the tier, and the Draft's Quote follows.
 */
export function TierSelect({ requestId, line, number }: TierSelectProps) {
  const tiers = useTypeTiers(line.typeId);
  const edit = useEditLine(requestId);
  const options = tiers.data ?? [];
  if (options.length === 0 && !line.tier) return <>{tiers.isPending ? '…' : 'No tiers'}</>;

  function choose(value: string) {
    edit.mutate({
      lineId: line.id,
      edit: {
        quantity: line.quantity,
        start: line.window.start,
        end: line.window.end,
        tierId: value === '' ? null : Number(value),
      },
    });
  }

  return (
    <>
      <select
        className="tier-select"
        aria-label={`Line ${number} tier`}
        value={line.tier ?? ''}
        disabled={edit.isPending}
        onChange={(event) => choose(event.target.value)}
      >
        <option value="">None</option>
        {options.map((tier) => (
          <option key={tier.id} value={tier.id}>
            {tier.name} ×{tier.premium}
          </option>
        ))}
      </select>
      <ProblemMessage error={edit.error} />
    </>
  );
}
