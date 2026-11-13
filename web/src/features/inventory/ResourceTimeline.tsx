import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import type { Holding, HoldingState, Id, Me } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { hasAnyRole } from '../../auth/roles';
import {
  Button,
  Field,
  Loading,
  ProblemMessage,
  Table,
  formatWindow,
  toInstant,
  toPickerValue,
  todayInCompanyZone,
  type Column,
  type Tone,
} from '../../ui';
import { useHolders } from './queries';

/** Each state in the words of the SRS, with the tone of its badge and of its part of the bar. */
const STATES: Record<HoldingState, { label: string; tone: Tone }> = {
  FREE: { label: 'Free', tone: 'neutral' },
  HELD: { label: 'On Hold', tone: 'info' },
  ALLOCATED: { label: 'Allocated', tone: 'success' },
};

/**
 * The timeline of an Exclusive Resource (SRS Inventory.View.Holder): for a window, by default today
 * from 08:00 to 18:00 company time, each part free, on Hold, or allocated, with the holding Team and
 * Request where the server shows them. The table is the timeline; the bar above it only repeats it.
 */
export function ResourceTimeline({ resourceId }: { resourceId: Id }) {
  const me = useCurrentMember();
  const [shown, setShown] = useState(() => {
    const today = todayInCompanyZone();
    return { from: toInstant(`${today}T08:00`), to: toInstant(`${today}T18:00`) };
  });
  const [from, setFrom] = useState(toPickerValue(shown.from));
  const [to, setTo] = useState(toPickerValue(shown.to));
  const holders = useHolders(resourceId, shown.from, shown.to);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (from && to) setShown({ from: toInstant(from), to: toInstant(to) });
  }

  return (
    <section className="section" aria-labelledby="timeline-title">
      <h2 id="timeline-title" className="section-title">
        Timeline
      </h2>
      <form className="window-form" onSubmit={handleSubmit}>
        <Field label="From">
          <input type="datetime-local" step={3600} value={from} required onChange={(event) => setFrom(event.target.value)} />
        </Field>
        <Field label="To">
          <input type="datetime-local" step={3600} value={to} required onChange={(event) => setTo(event.target.value)} />
        </Field>
        <Button type="submit" variant="secondary">
          Show timeline
        </Button>
      </form>
      {holders.isError ? (
        <ProblemMessage error={holders.error} />
      ) : holders.isPending ? (
        <Loading />
      ) : (
        <>
          <TimelineBar parts={holders.data} />
          <Table
            label={`Timeline ${formatWindow({ start: shown.from, end: shown.to })}`}
            columns={columns(me)}
            rows={holders.data}
            rowKey={(part) => part.window.start}
          />
        </>
      )}
    </section>
  );
}

function columns(me: Me): Column<Holding>[] {
  return [
    { header: 'When', cell: (part) => formatWindow(part.window) },
    {
      header: 'Status',
      cell: (part) => <span className={`badge badge--${STATES[part.state].tone}`}>{STATES[part.state].label}</span>,
    },
    { header: 'Held by', cell: (part) => <Holder part={part} me={me} /> },
  ];
}

function Holder({ part, me }: { part: Holding; me: Me }) {
  if (part.state === 'FREE') return <>—</>;
  if (part.team === null || part.request === null) {
    return <span className="timeline-hidden">Team and Request not shown to you</span>;
  }
  // The Request screen is for the Team's Manager and the Capacity Planner.
  const opensRequest = hasAnyRole(me, ['PLANNER']) || (hasAnyRole(me, ['MANAGER']) && part.team.id === me.team.id);
  return (
    <>
      {part.team.name},{' '}
      {opensRequest ? <Link to={`/requests/${part.request}`}>Request #{part.request}</Link> : `Request #${part.request}`}
      {part.priority && (
        <>
          {' '}
          <span className="badge badge--warning">Priority</span>
        </>
      )}
    </>
  );
}

/** The parts as a bar, each as wide as it is long. Decorative: the table says the same in words. */
function TimelineBar({ parts }: { parts: Holding[] }) {
  return (
    <div className="timeline-bar" aria-hidden="true">
      {parts.map((part) => (
        <span
          key={part.window.start}
          className={`timeline-bar__part timeline-bar__part--${STATES[part.state].tone}`}
          style={{ flexGrow: Date.parse(part.window.end) - Date.parse(part.window.start) }}
        />
      ))}
    </div>
  );
}
