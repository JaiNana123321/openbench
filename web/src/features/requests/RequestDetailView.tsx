import { Link } from 'react-router';
import type { Id, LineDetail, RequestEvent, RequestState } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  Button,
  EmptyState,
  formatCredits,
  formatEnum,
  formatInstant,
  formatWindow,
  Loading,
  PageHeader,
  ProblemMessage,
  StatusBadge,
  Table,
  type Column,
} from '../../ui';
import { useRequestDetail, useWithdrawRequest } from './queries';

/** The states a Request may be withdrawn from (design Table "Request transitions"); the server decides. */
const WITHDRAWABLE: ReadonlySet<RequestState> = new Set(['DRAFT', 'PENDING', 'WAITING']);

/**
 * The Request detail: its state, each line with its state, the Resources it is allocated on, its
 * locked Quote, why it waits or was Dropped, and its waitlist position, and the history. Its Team's
 * Manager may withdraw it while the state allows.
 */
export function RequestDetailView({ id }: { id: Id }) {
  const me = useCurrentMember();
  const detail = useRequestDetail(id);
  const withdraw = useWithdrawRequest(id);

  if (detail.isError) return <ErrorPage error={detail.error} />;
  if (detail.isPending) return <Loading />;

  const { request, lines, history } = detail.data;
  const number = new Map(lines.map((line, index) => [line.line.id, index + 1]));
  const canWithdraw =
    WITHDRAWABLE.has(request.state) && me.roles.includes('MANAGER') && me.team.id === request.team.id;

  return (
    <>
      <PageHeader
        title={`Request #${request.id}`}
        subtitle={
          <>
            {request.team.name} Team <StatusBadge status={request.state} />
            {request.submittedAt && <> Submitted {formatInstant(request.submittedAt)}</>}
          </>
        }
        actions={
          <>
            {canWithdraw && (
              <Button variant="danger" busy={withdraw.isPending} onClick={() => withdraw.mutate()}>
                Withdraw
              </Button>
            )}
            <Link to="/requests" className="button button--secondary">
              All Requests
            </Link>
          </>
        }
      />
      <ProblemMessage error={withdraw.error} />

      <section className="section" aria-labelledby="lines-title">
        <h2 id="lines-title" className="section-title">
          Lines
        </h2>
        <Table
          label="Lines"
          columns={lineColumns(number)}
          rows={lines}
          rowKey={(line) => line.line.id}
          empty={<EmptyState title="This Request has no lines." />}
        />
      </section>

      <section className="section" aria-labelledby="history-title">
        <h2 id="history-title" className="section-title">
          History
        </h2>
        <ol className="timeline" aria-labelledby="history-title">
          {history.map((event, index) => (
            <li key={index} className="timeline__entry">
              <span className="timeline__time">{formatInstant(event.at)}</span>
              <span>
                {event.actorName ?? 'System'}: {eventText(event, number)}
              </span>
              {event.note && <span className="timeline__note">{event.note}</span>}
            </li>
          ))}
        </ol>
      </section>
    </>
  );
}

function lineColumns(number: Map<Id, number>): Column<LineDetail>[] {
  return [
    { header: '#', cell: ({ line }) => number.get(line.id), numeric: true },
    { header: 'Resource Type', cell: ({ line }) => `${line.typeName} (${formatEnum(line.allocationClass)})` },
    { header: 'Quantity', cell: ({ line }) => amountText(line.allocationClass, line.quantity), numeric: true },
    { header: 'Window', cell: ({ line }) => formatWindow(line.window) },
    { header: 'Required', cell: ({ line }) => (line.required ? 'Required' : 'Optional') },
    { header: 'State', cell: ({ line }) => <StatusBadge status={line.state} /> },
    {
      header: 'Assigned',
      cell: ({ line, assignedResources }) =>
        assignedResources.length > 0
          ? assignedResources.map((resource) => resource.name).join(', ')
          : line.namedResourceName
            ? `Asked for ${line.namedResourceName}`
            : '—',
    },
    {
      header: 'Locked Quote',
      cell: ({ line }) => (line.quote === null ? 'No price' : formatCredits(line.quote)),
      numeric: true,
    },
    {
      header: 'Reason',
      cell: ({ reason, reasonMessage }) =>
        reason === null ? (
          '—'
        ) : (
          <>
            <strong>{formatEnum(reason)}</strong>
            {reasonMessage && <span className="timeline__note">{reasonMessage}</span>}
          </>
        ),
    },
    {
      header: 'Waitlist',
      cell: ({ waitlistPosition }) => (waitlistPosition === null ? '—' : `Position ${waitlistPosition}`),
    },
  ];
}

function eventText(event: RequestEvent, number: Map<Id, number>): string {
  const subject = event.line === null ? 'Request' : `Line ${number.get(event.line) ?? event.line}`;
  if (event.fromState === null) return `${subject} created as ${formatEnum(event.toState)}`;
  return `${subject} ${formatEnum(event.fromState)} to ${formatEnum(event.toState)}`;
}

function amountText(allocationClass: string, quantity: number): string {
  if (allocationClass === 'METERED') return `${quantity} ${quantity === 1 ? 'unit' : 'units'}`;
  return `${quantity} ${quantity === 1 ? 'instance' : 'instances'}`;
}
