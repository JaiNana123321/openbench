import { useState } from 'react';
import type { Id, LedgerTransaction, Period, Statement } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  EmptyState,
  Field,
  formatCredits,
  formatDayRange,
  formatEnum,
  formatInstant,
  Loading,
  PageHeader,
  Table,
  todayInCompanyZone,
  type Column,
} from '../../ui';
import { usePeriods, useStatement, useTeams } from './queries';

/**
 * The Statement screen: a Team's Account and every Transaction of a Period. A Manager sees their
 * own Team; a Capacity Planner picks any Team. The Period defaults to the one containing today.
 */
export function StatementPage() {
  const me = useCurrentMember();
  const isPlanner = me.roles.includes('PLANNER');
  const [teamId, setTeamId] = useState<Id>(me.team.id);
  const [chosenPeriodId, setChosenPeriodId] = useState<Id | undefined>();
  const periods = usePeriods();
  const teams = useTeams(isPlanner);
  const periodId = chosenPeriodId ?? defaultPeriod(periods.data ?? [])?.id;
  const statement = useStatement(teamId, periodId);

  if (periods.isError) return <ErrorPage error={periods.error} />;
  if (teams.isError) return <ErrorPage error={teams.error} />;
  if (statement.isError) return <ErrorPage error={statement.error} />;
  if (periods.isPending || (isPlanner && teams.isPending)) return <Loading />;

  const shown = statement.data;
  return (
    <>
      <PageHeader
        title="Statement"
        subtitle={shown ? `${shown.team.name} Team, ${periodText(shown.period)}` : undefined}
      />

      <section className="section" aria-label="Choose a Statement">
        {isPlanner && (
          <Field label="Team">
            <select value={teamId} onChange={(event) => setTeamId(Number(event.target.value))}>
              {teams.data?.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.name}
                </option>
              ))}
            </select>
          </Field>
        )}
        {periods.data.length === 0 ? (
          <EmptyState title="No Periods have been set up yet." />
        ) : (
          <Field label="Period">
            <select value={periodId} onChange={(event) => setChosenPeriodId(Number(event.target.value))}>
              {periods.data.map((period) => (
                <option key={period.id} value={period.id}>
                  {periodText(period)}
                </option>
              ))}
            </select>
          </Field>
        )}
      </section>

      {periodId !== undefined &&
        (statement.isPending ? <Loading /> : <StatementBody statement={statement.data} />)}
    </>
  );
}

function StatementBody({ statement }: { statement: Statement }) {
  const { account, opening, totals, closing } = statement;
  return (
    <>
      <section className="section panel" aria-labelledby="account-title">
        <h2 id="account-title" className="section-title">
          Account now
        </h2>
        <dl className="details">
          <dt>Granted</dt>
          <dd className="numeric">{formatCredits(account.granted)}</dd>
          <dt>Earned</dt>
          <dd className="numeric">{formatCredits(account.earned)}</dd>
          <dt>Held</dt>
          <dd className="numeric">{formatCredits(account.held)}</dd>
          <dt>Balance</dt>
          <dd className="numeric">{formatCredits(account.balance)}</dd>
          <dt>Available Balance</dt>
          <dd className="numeric">{formatCredits(account.available)}</dd>
        </dl>
      </section>

      <section className="section panel" aria-labelledby="period-title">
        <h2 id="period-title" className="section-title">
          This Period
        </h2>
        <dl className="details">
          <dt>Opening granted</dt>
          <dd className="numeric">{formatCredits(opening.granted)}</dd>
          <dt>Opening earned</dt>
          <dd className="numeric">{formatCredits(opening.earned)}</dd>
          <dt>Grants</dt>
          <dd className="numeric">{formatCredits(totals.grants)}</dd>
          <dt>Charges</dt>
          <dd className="numeric">{formatCredits(totals.charges)}</dd>
          <dt>Earnings</dt>
          <dd className="numeric">{formatCredits(totals.earnings)}</dd>
          <dt>Refunds</dt>
          <dd className="numeric">{formatCredits(totals.refunds)}</dd>
          <dt>Expired</dt>
          <dd className="numeric">{formatCredits(totals.expired)}</dd>
          <dt>Closing granted</dt>
          <dd className="numeric">{formatCredits(closing.granted)}</dd>
          <dt>Closing earned</dt>
          <dd className="numeric">{formatCredits(closing.earned)}</dd>
        </dl>
      </section>

      <section className="section" aria-labelledby="transactions-title">
        <h2 id="transactions-title" className="section-title">
          Transactions
        </h2>
        <Table
          label="Transactions"
          columns={TRANSACTION_COLUMNS}
          rows={statement.transactions}
          rowKey={(transaction) => transaction.id}
          empty={<EmptyState title="No Transactions in this Period." />}
        />
      </section>
    </>
  );
}

const TRANSACTION_COLUMNS: Column<LedgerTransaction>[] = [
  { header: 'Time', cell: (row) => formatInstant(row.postedAt) },
  { header: 'Kind', cell: (row) => formatEnum(row.kind) },
  { header: 'Amount', cell: (row) => formatCredits(row.amount), numeric: true },
  { header: 'Granted part', cell: (row) => formatCredits(row.grantedPart), numeric: true },
  { header: 'Earned part', cell: (row) => formatCredits(row.earnedPart), numeric: true },
  { header: 'Request line', cell: (row) => (row.line === null ? '—' : `Line ${row.line}`) },
  { header: 'By', cell: (row) => row.actorName ?? 'System' },
  { header: 'Reason', cell: (row) => row.reason ?? '' },
];

function periodText(period: Period): string {
  return formatDayRange({ from: period.startsOn, to: period.endsOn });
}

/** The Period containing today, else the latest one that has started, else the first. */
function defaultPeriod(periods: readonly Period[]): Period | undefined {
  const today = todayInCompanyZone();
  const started = periods.filter((period) => period.startsOn <= today);
  const current = started.find((period) => today <= period.endsOn);
  return current ?? started.at(-1) ?? periods[0];
}
