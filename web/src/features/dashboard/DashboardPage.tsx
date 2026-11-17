import { useState } from 'react';
import { Link } from 'react-router';
import type {
  Id,
  Period,
  PersonDays,
  TeamCredits,
  TeamTypeUsage,
  TypeIdle,
  TypeUtilization,
  TypeWaiting,
} from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  EmptyState,
  Field,
  formatCredits,
  formatDayRange,
  formatEnum,
  formatHours,
  formatPercent,
  Loading,
  PageHeader,
  ProblemMessage,
  Table,
  todayInCompanyZone,
  type Column,
} from '../../ui';
import {
  useIdle,
  usePeriods,
  usePersonDays,
  useTeamCredits,
  useUsageComparison,
  useUtilization,
  useWaiting,
} from './queries';

/** One row of the utilization table: a type's Period figures, idle to date, idle week, and waiting lines. */
interface TypeRow {
  utilization: TypeUtilization;
  idle: TypeIdle | undefined;
  waiting: TypeWaiting | undefined;
}

/** One row of the credits table: a Team's credits with its person-days. */
interface TeamRow {
  credits: TeamCredits;
  days: PersonDays | undefined;
}

/**
 * The showback dashboard. Utilization, idle capacity, and waiting lines per type for a Manager,
 * a Resource Owner (their types only), and the Capacity Planner; Team credits and person-days for
 * a Manager (their Team) and the Capacity Planner (every Team). The server applies the scope; the
 * page only leaves out the credits a Resource Owner may not ask for. Requested against used
 * unit-hours per Team and Metered type, for every role, in the server's scope. The Period defaults
 * to the current one, and any past Period can be chosen.
 */
export function DashboardPage() {
  const me = useCurrentMember();
  const seesCredits = me.roles.includes('MANAGER') || me.roles.includes('PLANNER');
  const [chosenPeriodId, setChosenPeriodId] = useState<Id | undefined>();
  const periods = usePeriods();
  const choices = pastAndCurrent(periods.data ?? []);
  const periodId = chosenPeriodId ?? choices.at(-1)?.id;
  const utilization = useUtilization(periodId);
  const idle = useIdle();
  const waiting = useWaiting();
  const credits = useTeamCredits(periodId, seesCredits);
  const personDays = usePersonDays(periodId, seesCredits);
  const usage = useUsageComparison(periodId);

  for (const query of [periods, utilization, idle, waiting, credits, personDays]) {
    if (query.isError) return <ErrorPage error={query.error} />;
  }
  if (periods.isPending) return <Loading />;
  const period = choices.find((choice) => choice.id === periodId);

  return (
    <>
      <PageHeader title="Dashboard" subtitle={period ? periodText(period) : undefined} />

      <section className="section" aria-label="Choose a Period">
        {choices.length === 0 ? (
          <EmptyState title="No Periods have been set up yet." />
        ) : (
          <Field label="Period">
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

      <section className="section" aria-labelledby="utilization-title">
        <h2 id="utilization-title" className="section-title">
          Utilization
        </h2>
        {utilization.isPending || idle.isPending || waiting.isPending ? (
          periodId !== undefined && <Loading />
        ) : (
          <Table
            label="Utilization"
            columns={typeColumns}
            rows={typeRows(utilization.data ?? [], idle.data ?? [], waiting.data ?? [])}
            rowKey={(row) => row.utilization.type}
            empty={<EmptyState title="No Exclusive or Metered types in your view." />}
          />
        )}
      </section>

      {seesCredits && (
        <section className="section" aria-labelledby="credits-title">
          <h2 id="credits-title" className="section-title">
            Team credits
          </h2>
          {credits.isPending || personDays.isPending ? (
            periodId !== undefined && <Loading />
          ) : (
            <Table
              label="Team credits"
              columns={teamColumns}
              rows={teamRows(credits.data ?? [], personDays.data ?? [])}
              rowKey={(row) => row.credits.team}
              empty={<EmptyState title="No Teams in your view." />}
            />
          )}
        </section>
      )}

      <section className="section" aria-labelledby="usage-title">
        <h2 id="usage-title" className="section-title">
          Requested vs used
        </h2>
        {usage.isError ? (
          <ProblemMessage error={usage.error} />
        ) : usage.isPending ? (
          periodId !== undefined && <Loading />
        ) : (
          <Table
            label="Requested vs used"
            columns={usageColumns}
            rows={usage.data}
            rowKey={(row) => `${row.team}-${row.type}`}
            empty={<EmptyState title="No Metered allocations in your view for this Period." />}
          />
        )}
      </section>
    </>
  );
}

const usageColumns: Column<TeamTypeUsage>[] = [
  { header: 'Team', cell: (row) => row.teamName },
  { header: 'Metered type', cell: (row) => row.typeName },
  { header: 'Requested unit-hours', cell: (row) => formatHours(row.requestedUnitHours), numeric: true },
  { header: 'Used unit-hours', cell: (row) => formatHours(row.usedUnitHours), numeric: true },
  { header: 'Allocations recorded', cell: (row) => row.recorded, numeric: true },
  { header: 'Not yet recorded', cell: (row) => row.unrecorded, numeric: true },
];

const typeColumns: Column<TypeRow>[] = [
  { header: 'Type', cell: (row) => row.utilization.name },
  { header: 'Class', cell: (row) => formatEnum(row.utilization.allocationClass) },
  { header: 'Utilization', cell: (row) => formatPercent(row.utilization.utilizationPercent), numeric: true },
  { header: 'Allocated hours', cell: (row) => formatHours(row.utilization.allocatedHours), numeric: true },
  { header: 'Available hours', cell: (row) => formatHours(row.utilization.availableHours), numeric: true },
  { header: 'Idle, Period to date', cell: (row) => formatHours(row.utilization.idleHoursToDate), numeric: true },
  { header: 'Idle, next 7 days', cell: (row) => (row.idle ? formatHours(row.idle.idleHours) : '—'), numeric: true },
  { header: 'Waiting lines', cell: (row) => row.waiting?.waiting ?? 0, numeric: true },
];

const teamColumns: Column<TeamRow>[] = [
  {
    header: 'Team',
    cell: (row) => (
      <Link to={`/statement?team=${row.credits.team}&period=${row.credits.period}`}>{row.credits.teamName}</Link>
    ),
  },
  { header: 'Grants', cell: (row) => formatCredits(row.credits.grants), numeric: true },
  { header: 'Charges', cell: (row) => formatCredits(row.credits.charges), numeric: true },
  { header: 'Earnings', cell: (row) => formatCredits(row.credits.earnings), numeric: true },
  { header: 'Refunds', cell: (row) => formatCredits(row.credits.refunds), numeric: true },
  { header: 'Expired', cell: (row) => formatCredits(row.credits.expired), numeric: true },
  { header: 'Balance', cell: (row) => formatCredits(row.credits.balance), numeric: true },
  { header: 'Available Balance', cell: (row) => formatCredits(row.credits.available), numeric: true },
  { header: 'Person-days lent', cell: (row) => row.days?.lent ?? 0, numeric: true },
  { header: 'Person-days borrowed', cell: (row) => row.days?.borrowed ?? 0, numeric: true },
];

function typeRows(utilization: TypeUtilization[], idle: TypeIdle[], waiting: TypeWaiting[]): TypeRow[] {
  const idleOf = new Map(idle.map((row) => [row.type, row]));
  const waitingOf = new Map(waiting.map((row) => [row.type, row]));
  return utilization.map((row) => ({ utilization: row, idle: idleOf.get(row.type), waiting: waitingOf.get(row.type) }));
}

function teamRows(credits: TeamCredits[], days: PersonDays[]): TeamRow[] {
  const daysOf = new Map(days.map((row) => [row.team, row]));
  return credits.map((row) => ({ credits: row, days: daysOf.get(row.team) }));
}

function periodText(period: Period): string {
  return formatDayRange({ from: period.startsOn, to: period.endsOn });
}

/** The Periods that have started by today, oldest first: the current one and every past one. */
function pastAndCurrent(periods: readonly Period[]): Period[] {
  const today = todayInCompanyZone();
  return periods.filter((period) => period.startsOn <= today);
}
