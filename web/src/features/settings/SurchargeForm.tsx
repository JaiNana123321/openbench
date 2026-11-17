import { useState, type FormEvent } from 'react';
import { fieldError } from '../../api/client';
import type { Id } from '../../api/types';
import { Button, Field, ProblemMessage } from '../../ui';
import { useUpdateSetting } from './queries';

/**
 * The demand surcharge of a type, saved under surcharge.{typeId} (SRS Ledger.Surcharge): on with a
 * rate, or off (rate 0). Turning it on suggests a rate of 1.0, which the Planner may change. A Quote
 * is multiplied by 1 + rate × the type's peak fill in the line's window when it is quoted.
 */
export function SurchargeForm({ typeId, rate }: { typeId: Id; rate: number }) {
  const update = useUpdateSetting();
  const [on, setOn] = useState(rate > 0);
  const [text, setText] = useState(rate > 0 ? String(rate) : '1.0');

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    update.mutate({ key: `surcharge.${typeId}`, value: on ? Number(text) : 0 });
  }

  return (
    <form className="form" onSubmit={handleSubmit} aria-label="Demand surcharge">
      <h3 className="form__heading">Demand surcharge</h3>
      <p className="field__hint">
        A Quote is multiplied by 1 + rate × the share of the type already taken in the line's busiest hour. It locks
        with the Quote at submission.
      </p>
      <label className="checkbox">
        <input type="checkbox" checked={on} onChange={(event) => setOn(event.target.checked)} />
        Surcharge on
      </label>
      {on && (
        <Field label="Rate" error={fieldError(update.error, 'value')}>
          <input type="number" step="any" min={0} required value={text} onChange={(event) => setText(event.target.value)} />
        </Field>
      )}
      <div className="actions">
        <Button type="submit" busy={update.isPending}>
          Save surcharge
        </Button>
        {update.isSuccess && <span role="status">Saved.</span>}
      </div>
      <ProblemMessage error={update.error} />
    </form>
  );
}
