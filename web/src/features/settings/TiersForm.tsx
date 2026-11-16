import { useState, type FormEvent } from 'react';
import type { Id, ServiceTier } from '../../api/types';
import { Button, Field, ProblemMessage } from '../../ui';
import { useUpdateSetting } from './queries';

interface TierRow {
  /** The tier's id; absent for a tier added here. */
  id?: Id;
  name: string;
  premium: string;
}

/**
 * Every service tier of a Metered type, saved together: tiers.{typeId} (SRS Metered.Tier.Define).
 * A kept tier keeps its id, so the Planner edits its premium; a removed tier that a line refers to
 * is refused by the server.
 */
export function TiersForm({ typeId, tiers }: { typeId: Id; tiers: ServiceTier[] }) {
  const update = useUpdateSetting();
  const [rows, setRows] = useState<TierRow[]>(() =>
    tiers.map((tier) => ({ id: tier.id, name: tier.name, premium: String(tier.premium) })),
  );

  function change(index: number, row: Partial<TierRow>) {
    setRows(rows.map((existing, i) => (i === index ? { ...existing, ...row } : existing)));
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    update.mutate({
      key: `tiers.${typeId}`,
      value: rows.map((row) => ({ id: row.id ?? null, name: row.name, premium: Number(row.premium) })),
    });
  }

  return (
    <form className="form" onSubmit={handleSubmit} aria-label="Service tiers">
      <h3 className="form__heading">Service tiers</h3>
      <p className="field__hint">
        A tier's premium (at least 1.0) multiplies the Unit Price of a line marked with it. With a bump cutoff, a line
        Bumps lines of lower tiers when the pool is full; a line without a tier ranks lowest.
      </p>
      {rows.map((row, index) => (
        <fieldset key={row.id ?? `new-${index}`} className="tier-row" aria-label={`Tier ${index + 1}`}>
          <Field label="Name">
            <input required value={row.name} onChange={(event) => change(index, { name: event.target.value })} />
          </Field>
          <Field label="Premium">
            <input
              type="number"
              step="any"
              min={1}
              required
              value={row.premium}
              onChange={(event) => change(index, { premium: event.target.value })}
            />
          </Field>
          <Button variant="secondary" onClick={() => setRows(rows.filter((_, i) => i !== index))}>
            Remove tier {index + 1}
          </Button>
        </fieldset>
      ))}
      <div className="actions">
        <Button variant="secondary" onClick={() => setRows([...rows, { name: '', premium: '1' }])}>
          Add tier
        </Button>
        <Button type="submit" busy={update.isPending}>
          Save tiers
        </Button>
        {update.isSuccess && <span role="status">Saved.</span>}
      </div>
      <ProblemMessage error={update.error} />
    </form>
  );
}
