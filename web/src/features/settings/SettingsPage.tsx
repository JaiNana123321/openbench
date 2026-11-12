import { useState, type FormEvent, type ReactNode } from 'react';
import { fieldError } from '../../api/client';
import type {
  DayOfWeek,
  Id,
  Period,
  PlannerSettings,
  PriceBand,
  RefundStep,
  ResourceType,
  SettingChangeEntry,
  TeamRef,
  TypePricing,
} from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  Button,
  EmptyState,
  Field,
  formatCredits,
  formatDay,
  formatDayRange,
  formatInstant,
  Loading,
  PageHeader,
  ProblemMessage,
  Table,
  todayInCompanyZone,
  type Column,
} from '../../ui';
import {
  CHANGES_PAGE_SIZE,
  useMidPeriodGrant,
  usePeriods,
  usePlannedGrants,
  useResourceTypes,
  useSettingChanges,
  useSettings,
  useTeams,
  useUpdateSetting,
} from './queries';

/**
 * /settings: the Capacity Planner sets every policy value as data. Each form saves one setting
 * through its key and shows a refusal by its message; the history lists every change. A change
 * affects only what comes after it: a locked Quote and a posted Transaction never change.
 */
export function SettingsPage() {
  const settings = useSettings();
  const types = useResourceTypes();
  const teams = useTeams();
  const periods = usePeriods();

  if (settings.isError) return <ErrorPage error={settings.error} />;
  if (types.isError) return <ErrorPage error={types.error} />;
  if (teams.isError) return <ErrorPage error={teams.error} />;
  if (periods.isError) return <ErrorPage error={periods.error} />;
  if (settings.isPending || types.isPending || teams.isPending || periods.isPending) return <Loading />;

  const priced = types.data.filter((type) => type.allocationClass !== 'MATCHED');
  return (
    <>
      <PageHeader
        title="Settings"
        subtitle="Every policy value, set as data. A change affects only what comes after it."
      />
      <GeneralSection settings={settings.data} />
      <TypeSection settings={settings.data} types={priced} />
      <TeamSection settings={settings.data} types={priced} teams={teams.data} periods={periods.data} />
      <MidPeriodGrantSection teams={teams.data} />
      <HistorySection types={types.data} teams={teams.data} periods={periods.data} />
    </>
  );
}

// ---------------------------------------------------------------- one setting

interface ValueFormProps {
  /** The form's name, the field's label, and the Save button's context. */
  label: string;
  settingKey: string;
  /** The value now, as the input shows it; '' for not set. */
  current: string;
  /** The JSON value to send for the input's text. */
  toValue: (text: string) => unknown;
  hint?: ReactNode;
  step?: string;
}

/** One setting with its own Save: PUT /api/settings/{key}. */
function ValueForm({ label, settingKey, current, toValue, hint, step = 'any' }: ValueFormProps) {
  const update = useUpdateSetting();
  const [text, setText] = useState(current);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    update.mutate({ key: settingKey, value: toValue(text) });
  }

  return (
    <form className="setting-form" onSubmit={handleSubmit} aria-label={label}>
      <Field label={label} hint={hint} error={fieldError(update.error, 'value')}>
        <input type="number" step={step} value={text} onChange={(event) => setText(event.target.value)} />
      </Field>
      <div className="actions">
        <Button type="submit" busy={update.isPending}>
          Save
        </Button>
        {update.isSuccess && <span role="status">Saved.</span>}
      </div>
      <ProblemMessage error={update.error} />
    </form>
  );
}

const number = (text: string) => Number(text);
const hours = (text: string) => `PT${Number(text)}H`;

/** The whole hours of an ISO-8601 duration such as "PT48H" or "PT0S"; '' for none. */
function hoursOf(duration: string | null | undefined): string {
  if (!duration) return '';
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(duration);
  return match ? String(Number(match[1] ?? 0)) : '';
}

// ---------------------------------------------------------------- sections

function GeneralSection({ settings }: { settings: PlannerSettings }) {
  return (
    <section className="section" aria-labelledby="settings-general">
      <h2 className="section-title" id="settings-general">
        General
      </h2>
      <div className="panel setting-grid">
        <ValueForm
          label="Day Rate"
          settingKey="day_rate"
          current={settings.dayRate.toFixed(2)}
          toValue={number}
          hint="Credits per person-day of a Loan."
        />
        <ValueForm
          label="Priority premium"
          settingKey="priority_premium"
          current={String(settings.priorityPremium)}
          toValue={number}
          hint="The multiple of the Unit Price a Priority line pays; at least 1."
        />
        <ValueForm
          label="Objection Window (working days)"
          settingKey="objection_window_working_days"
          current={String(settings.objectionWindowWorkingDays)}
          toValue={number}
          step="1"
        />
        <ValueForm
          label="Period length (months)"
          settingKey="period_months"
          current={String(settings.periodMonths)}
          toValue={number}
          step="1"
          hint={`Applies to Periods not yet started. The current Period is ${formatDayRange({
            from: settings.currentPeriod.startsOn,
            to: settings.currentPeriod.endsOn,
          })}.`}
        />
      </div>
    </section>
  );
}

function TypeSection({ settings, types }: { settings: PlannerSettings; types: ResourceType[] }) {
  const [chosen, setChosen] = useState<Id | undefined>();
  const type = types.find((candidate) => candidate.id === chosen) ?? types[0];
  return (
    <section className="section" aria-labelledby="settings-types">
      <h2 className="section-title" id="settings-types">
        Prices by Resource Type
      </h2>
      {type === undefined ? (
        <EmptyState title="No Exclusive or Metered Resource Type yet." />
      ) : (
        <div className="panel">
          <Field label="Resource Type">
            <select value={type.id} onChange={(event) => setChosen(Number(event.target.value))}>
              {types.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.name}
                </option>
              ))}
            </select>
          </Field>
          <TypePrices key={type.id} type={type} pricing={settings.pricing[type.id]} />
        </div>
      )}
    </section>
  );
}

function TypePrices({ type, pricing }: { type: ResourceType; pricing: TypePricing | undefined }) {
  return (
    <>
      <div className="setting-grid">
        <ValueForm
          label="Unit Price"
          settingKey={`unit_price.${type.id}`}
          current={pricing ? pricing.unitPrice.toFixed(2) : ''}
          toValue={number}
          hint={type.allocationClass === 'EXCLUSIVE' ? 'Credits per instance-hour.' : 'Credits per unit-hour.'}
        />
        {type.allocationClass === 'EXCLUSIVE' && (
          <ValueForm
            label="Bump cutoff (hours)"
            settingKey={`bump_cutoff.${type.id}`}
            current={hoursOf(pricing?.bumpCutoff)}
            toValue={hours}
            step="1"
            hint="A Priority line may not Bump within this many hours of the window's start."
          />
        )}
      </div>
      <BandsForm typeId={type.id} bands={pricing?.bands ?? []} />
      <RefundScheduleForm typeId={type.id} steps={pricing?.refundSchedule.steps ?? []} />
    </>
  );
}

const WEEKDAYS: { day: DayOfWeek; label: string }[] = [
  { day: 'MONDAY', label: 'Mon' },
  { day: 'TUESDAY', label: 'Tue' },
  { day: 'WEDNESDAY', label: 'Wed' },
  { day: 'THURSDAY', label: 'Thu' },
  { day: 'FRIDAY', label: 'Fri' },
  { day: 'SATURDAY', label: 'Sat' },
  { day: 'SUNDAY', label: 'Sun' },
];

interface BandRow {
  weekdays: DayOfWeek[];
  start: string;
  end: string;
  multiplier: string;
}

/** Every Price Band of the type, saved together: price_bands.{typeId}. */
function BandsForm({ typeId, bands }: { typeId: Id; bands: PriceBand[] }) {
  const update = useUpdateSetting();
  const [rows, setRows] = useState<BandRow[]>(() =>
    bands.map((band) => ({
      weekdays: WEEKDAYS.map(({ day }) => day).filter((day) => band.weekdays.includes(day)),
      start: band.start.slice(0, 5),
      end: band.end.slice(0, 5),
      multiplier: String(band.multiplier),
    })),
  );

  function change(index: number, row: Partial<BandRow>) {
    setRows(rows.map((existing, i) => (i === index ? { ...existing, ...row } : existing)));
  }

  function toggle(index: number, day: DayOfWeek) {
    const days = rows[index].weekdays;
    change(index, {
      weekdays: WEEKDAYS.map((weekday) => weekday.day).filter((d) => (d === day ? !days.includes(d) : days.includes(d))),
    });
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    update.mutate({
      key: `price_bands.${typeId}`,
      value: rows.map((row) => ({ ...row, multiplier: Number(row.multiplier) })),
    });
  }

  return (
    <form className="form" onSubmit={handleSubmit} aria-label="Price Bands">
      <h3 className="form__heading">Price Bands</h3>
      <p className="field__hint">Hours no band covers count at ×1.0. An end of 00:00 is midnight. Bands may not overlap.</p>
      {rows.map((row, index) => (
        <fieldset key={index} className="band-row" aria-label={`Band ${index + 1}`}>
          <div className="band-row__days">
            {WEEKDAYS.map(({ day, label }) => (
              <label key={day} className="checkbox">
                <input type="checkbox" checked={row.weekdays.includes(day)} onChange={() => toggle(index, day)} />
                {label}
              </label>
            ))}
          </div>
          <Field label="Start">
            <input type="time" required value={row.start} onChange={(event) => change(index, { start: event.target.value })} />
          </Field>
          <Field label="End">
            <input type="time" required value={row.end} onChange={(event) => change(index, { end: event.target.value })} />
          </Field>
          <Field label="Multiplier">
            <input
              type="number"
              step="any"
              required
              value={row.multiplier}
              onChange={(event) => change(index, { multiplier: event.target.value })}
            />
          </Field>
          <Button variant="secondary" onClick={() => setRows(rows.filter((_, i) => i !== index))}>
            Remove band {index + 1}
          </Button>
        </fieldset>
      ))}
      <div className="actions">
        <Button
          variant="secondary"
          onClick={() => setRows([...rows, { weekdays: [], start: '09:00', end: '17:00', multiplier: '1' }])}
        >
          Add band
        </Button>
        <Button type="submit" busy={update.isPending}>
          Save Price Bands
        </Button>
        {update.isSuccess && <span role="status">Saved.</span>}
      </div>
      <ProblemMessage error={update.error} />
    </form>
  );
}

interface StepRow {
  hours: string;
  percent: string;
}

/** The type's whole Refund Schedule, saved together: refund_steps.{typeId}. */
function RefundScheduleForm({ typeId, steps }: { typeId: Id; steps: RefundStep[] }) {
  const update = useUpdateSetting();
  const [rows, setRows] = useState<StepRow[]>(() =>
    [...steps].reverse().map((step) => ({
      hours: hoursOf(step.minLead),
      percent: String(Math.round(step.fraction * 1000) / 10),
    })),
  );

  function change(index: number, row: Partial<StepRow>) {
    setRows(rows.map((existing, i) => (i === index ? { ...existing, ...row } : existing)));
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    update.mutate({
      key: `refund_steps.${typeId}`,
      value: rows.map((row) => ({ minLead: hours(row.hours), fraction: Number(row.percent) / 100 })),
    });
  }

  return (
    <form className="form" onSubmit={handleSubmit} aria-label="Refund Schedule">
      <h3 className="form__heading">Refund Schedule</h3>
      <p className="field__hint">
        A Release at least the lead time before a part starts returns that share of its Charge; no step may reach 100%.
      </p>
      {rows.map((row, index) => (
        <fieldset key={index} className="step-row" aria-label={`Step ${index + 1}`}>
          <Field label="Lead time (hours)">
            <input
              type="number"
              step="1"
              required
              value={row.hours}
              onChange={(event) => change(index, { hours: event.target.value })}
            />
          </Field>
          <Field label="Refund (%)">
            <input
              type="number"
              step="any"
              required
              value={row.percent}
              onChange={(event) => change(index, { percent: event.target.value })}
            />
          </Field>
          <Button variant="secondary" onClick={() => setRows(rows.filter((_, i) => i !== index))}>
            Remove step {index + 1}
          </Button>
        </fieldset>
      ))}
      <div className="actions">
        <Button variant="secondary" onClick={() => setRows([...rows, { hours: '0', percent: '0' }])}>
          Add step
        </Button>
        <Button type="submit" busy={update.isPending}>
          Save Refund Schedule
        </Button>
        {update.isSuccess && <span role="status">Saved.</span>}
      </div>
      <ProblemMessage error={update.error} />
    </form>
  );
}

interface TeamSectionProps {
  settings: PlannerSettings;
  types: ResourceType[];
  teams: TeamRef[];
  periods: Period[];
}

function TeamSection({ settings, types, teams, periods }: TeamSectionProps) {
  const [chosenTeam, setChosenTeam] = useState<Id | undefined>();
  const team = teams.find((candidate) => candidate.id === chosenTeam) ?? teams[0];
  const today = todayInCompanyZone();
  const coming = periods.filter((period) => period.startsOn > today);
  const [chosenPeriod, setChosenPeriod] = useState<Id | undefined>();
  const period = coming.find((candidate) => candidate.id === chosenPeriod) ?? coming[0];
  const metered = types.filter((type) => type.allocationClass === 'METERED');

  return (
    <section className="section" aria-labelledby="settings-teams">
      <h2 className="section-title" id="settings-teams">
        Grants and Quotas by Team
      </h2>
      {team === undefined ? (
        <EmptyState title="No Teams yet." />
      ) : (
        <div className="panel">
          <div className="setting-grid">
            <Field label="Team">
              <select value={team.id} onChange={(event) => setChosenTeam(Number(event.target.value))}>
                {teams.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>
                    {candidate.name}
                  </option>
                ))}
              </select>
            </Field>
            {period && (
              <Field label="From the Period">
                <select value={period.id} onChange={(event) => setChosenPeriod(Number(event.target.value))}>
                  {coming.map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>
                      {formatDayRange({ from: candidate.startsOn, to: candidate.endsOn })}
                    </option>
                  ))}
                </select>
              </Field>
            )}
          </div>
          <div className="setting-grid">
            {period ? (
              <PlannedGrantForm team={team} period={period} />
            ) : (
              <EmptyState title="No Period after the current one." />
            )}
            {metered.map((type) => (
              <ValueForm
                key={`quota.${team.id}.${type.id}`}
                label={`Quota for ${type.name} (units)`}
                settingKey={`quota.${team.id}.${type.id}`}
                current={String(settings.quotas[`${team.id}:${type.id}`] ?? '')}
                toValue={(text) => (text.trim() === '' ? null : Number(text))}
                step="1"
                hint="Leave empty for no Quota."
              />
            ))}
          </div>
        </div>
      )}
    </section>
  );
}

/** The Team's planned Grant from a coming Period on: grant.{teamId}.{periodId}. */
function PlannedGrantForm({ team, period }: { team: TeamRef; period: Period }) {
  const grants = usePlannedGrants(period.id);
  if (grants.isError) return <ProblemMessage error={grants.error} />;
  if (grants.isPending) return <Loading />;
  const planned = grants.data[team.id];
  return (
    <ValueForm
      key={`grant.${team.id}.${period.id}`}
      label="Planned Grant"
      settingKey={`grant.${team.id}.${period.id}`}
      current={planned === undefined ? '' : planned.toFixed(2)}
      toValue={number}
      hint={`Posted at the start of each Period from ${formatDay(period.startsOn)} until changed.`}
    />
  );
}

/** A mid-Period Grant to a Team, with a reason: POST /api/ledger/grants (led-grant-mid). */
function MidPeriodGrantSection({ teams }: { teams: TeamRef[] }) {
  const grant = useMidPeriodGrant();
  const [teamId, setTeamId] = useState<Id | undefined>(teams[0]?.id);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (teamId === undefined) return;
    grant.mutate(
      { teamId, amount: Number(amount), reason },
      {
        onSuccess: () => {
          setAmount('');
          setReason('');
        },
      },
    );
  }

  return (
    <section className="section" aria-labelledby="settings-mid-grant">
      <h2 className="section-title" id="settings-mid-grant">
        Mid-Period Grant
      </h2>
      <form className="panel form" onSubmit={handleSubmit} aria-label="Mid-Period Grant">
        <Field label="Team">
          <select value={teamId} onChange={(event) => setTeamId(Number(event.target.value))}>
            {teams.map((team) => (
              <option key={team.id} value={team.id}>
                {team.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Amount" error={fieldError(grant.error, 'amount')}>
          <input type="number" step="any" value={amount} onChange={(event) => setAmount(event.target.value)} />
        </Field>
        <Field label="Reason" error={fieldError(grant.error, 'reason')}>
          <input value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div className="actions">
          <Button type="submit" busy={grant.isPending}>
            Post Grant
          </Button>
          {grant.isSuccess && (
            <span role="status">Granted. The Team's Balance is now {formatCredits(grant.data.balance)}.</span>
          )}
        </div>
        <ProblemMessage error={grant.error} />
      </form>
    </section>
  );
}

interface HistoryProps {
  types: ResourceType[];
  teams: TeamRef[];
  periods: Period[];
}

/** The setting-change log, newest first. */
function HistorySection({ types, teams, periods }: HistoryProps) {
  const [page, setPage] = useState(0);
  const changes = useSettingChanges(page);
  const describe = keyDescriber(types, teams, periods);
  const columns: Column<SettingChangeEntry>[] = [
    { header: 'When', cell: (change) => formatInstant(change.at) },
    { header: 'Setting', cell: (change) => describe(change.key) },
    { header: 'Old value', cell: (change) => change.oldValue ?? 'None' },
    { header: 'New value', cell: (change) => change.newValue ?? 'None' },
    { header: 'By', cell: (change) => change.actorName ?? 'System' },
  ];

  let body: ReactNode;
  if (changes.isError) body = <ProblemMessage error={changes.error} />;
  else if (changes.isPending) body = <Loading />;
  else {
    const pages = Math.ceil(changes.data.totalElements / CHANGES_PAGE_SIZE);
    body = (
      <>
        <Table
          label="Setting changes"
          columns={columns}
          rows={changes.data.content}
          rowKey={(change) => change.id}
          empty={<EmptyState title="No setting has been changed yet." />}
        />
        {pages > 1 && (
          <div className="pager">
            <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </Button>
            <span>
              Page {page + 1} of {pages}
            </span>
            <Button variant="secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
              Next
            </Button>
          </div>
        )}
      </>
    );
  }

  return (
    <section className="section" aria-labelledby="settings-history">
      <h2 className="section-title" id="settings-history">
        History
      </h2>
      {body}
    </section>
  );
}

const SINGLE_SETTINGS: Record<string, string> = {
  day_rate: 'Day Rate',
  priority_premium: 'Priority premium',
  objection_window_working_days: 'Objection Window (working days)',
  period_months: 'Period length (months)',
};

const TYPE_SETTINGS: Record<string, string> = {
  unit_price: 'Unit Price',
  bump_cutoff: 'Bump cutoff',
  price_bands: 'Price Bands',
  refund_steps: 'Refund Schedule',
};

/** A setting's key in words, such as "Unit Price, Meeting room" for "unit_price.4". */
function keyDescriber(types: ResourceType[], teams: TeamRef[], periods: Period[]) {
  const typeName = (id: string) => types.find((type) => String(type.id) === id)?.name ?? `type ${id}`;
  const teamName = (id: string) => teams.find((team) => String(team.id) === id)?.name ?? `Team ${id}`;
  return (key: string): string => {
    const [name, first, second] = key.split('.');
    if (SINGLE_SETTINGS[name]) return SINGLE_SETTINGS[name];
    if (TYPE_SETTINGS[name]) return `${TYPE_SETTINGS[name]}, ${typeName(first)}`;
    if (name === 'quota') return `Quota, ${teamName(first)}, ${typeName(second)}`;
    if (name === 'grant') {
      const period = periods.find((candidate) => String(candidate.id) === second);
      return `Planned Grant, ${teamName(first)}${period ? ` from ${formatDay(period.startsOn)}` : ''}`;
    }
    return key;
  };
}
