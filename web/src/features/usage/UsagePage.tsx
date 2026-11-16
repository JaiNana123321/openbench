import { useState, type FormEvent } from 'react';
import type { Id, MeteredUsage, Period } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  Button,
  EmptyState,
  Field,
  formatDayRange,
  formatHours,
  formatInstant,
  formatWindow,
  Loading,
  PageHeader,
  ProblemMessage,
  Table,
  todayInCompanyZone,
  type Column,
} from '../../ui';
import { usePeriods, useRecordUsage, useUsage } from './queries';

/**
 * Metered usage for a Resource Owner (SRS Metered.Usage.Record): pick an ended Period, see the
 * Allocated Metered allocations of the types they own, and record the units each actually used.
 * The server lists only their types and refuses a value outside 0 to the allocation's units; the
 * value can be recorded again, and the last one wins. Usage changes no credits.
 */
export function UsagePage() {
  const [chosenPeriodId, setChosenPeriodId] = useState<Id | undefined>();
  const periods = usePeriods();
  const choices = ended(periods.data ?? []);
  const periodId = chosenPeriodId ?? choices.at(-1)?.id;
  const usage = useUsage(periodId);

  if (periods.isError) return <ErrorPage error={periods.error} />;
  if (usage.isError) return <ErrorPage error={usage.error} />;
  if (periods.isPending) return <Loading />;
  const period = choices.find((choice) => choice.id === periodId);

  return (
    <>
      <PageHeader title="Metered usage" subtitle={period ? periodText(period) : undefined} />

      <section className="section" aria-label="Choose a Period">
        {choices.length === 0 ? (
          <EmptyState title="No Period has ended yet." />
        ) : (
          <Field label="Period" hint="Usage is recorded once the Period has ended.">
            <select value={periodId} onChange={(event) => setChosenPeriodId(Number(event.target.value))}>
              {[...choices].reverse().map((choice) => (
                <option key={choice.id} value={choice.id}>
                  {periodText(choice)}
                </option>
              ))}
            </select>
          </Field>
        )}
      </section>

      {periodId !== undefined && (
        <section className="section" aria-labelledby="usage-title">
          <h2 id="usage-title" className="section-title">
            Allocations
          </h2>
          {usage.isPending ? (
            <Loading />
          ) : (
            <Table
              label="Metered allocations"
              columns={columns}
              rows={usage.data}
              rowKey={(row) => row.id}
              empty={<EmptyState title="No Metered allocations of your types in this Period." />}
            />
          )}
        </section>
      )}
    </>
  );
}

const columns: Column<MeteredUsage>[] = [
  { header: 'Team', cell: (row) => row.team.name },
  { header: 'Request', cell: (row) => `#${row.request}` },
  { header: 'Type', cell: (row) => row.typeName },
  { header: 'Window', cell: (row) => formatWindow(row.window) },
  { header: 'Units', cell: (row) => row.units, numeric: true },
  { header: 'Requested unit-hours', cell: (row) => formatHours(row.requestedUnitHours), numeric: true },
  { header: 'Units used', cell: (row) => <RecordUsage row={row} /> },
  {
    header: 'Recorded',
    cell: (row) => (row.recordedAt ? `${row.recordedBy ?? ''}, ${formatInstant(row.recordedAt)}` : 'Not yet'),
  },
];

/** The units used of one allocation: a number from 0 to its units, saved with Record. */
function RecordUsage({ row }: { row: MeteredUsage }) {
  const [value, setValue] = useState(row.usedUnits === null ? '' : String(row.usedUnits));
  const record = useRecordUsage(row.id);

  if (!row.recordable) return <span>After its Period ends</span>;

  function submit(event: FormEvent) {
    event.preventDefault();
    record.mutate(Number(value));
  }

  return (
    <form className="usage-form" onSubmit={submit} aria-label={`Usage of allocation ${row.id}`}>
      <input
        type="number"
        aria-label="Units used"
        min={0}
        max={row.units}
        step={1}
        required
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
      <Button type="submit" variant="secondary" busy={record.isPending}>
        Record
      </Button>
      <ProblemMessage error={record.error} />
    </form>
  );
}

function periodText(period: Period): string {
  return formatDayRange({ from: period.startsOn, to: period.endsOn });
}

/** The Periods that ended before today, oldest first. */
function ended(periods: readonly Period[]): Period[] {
  const today = todayInCompanyZone();
  return periods.filter((period) => period.endsOn < today);
}
