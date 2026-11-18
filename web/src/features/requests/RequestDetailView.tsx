import { useState } from 'react';
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
import { useReopenRequest, useRequestDetail, useWithdrawRequest } from './queries';
import { ReleaseForm } from './ReleaseForm';
import { PersonLines } from './PersonLines';
import { personAsk, personCount, whenText } from './personLineText';

/** The states a Request may be withdrawn from (design Table "Request transitions"); the server decides. */
const WITHDRAWABLE: ReadonlySet<RequestState> = new Set(['DRAFT', 'PENDING', 'WAITING']);

/** The line states a Release starts from; the server decides. */
const RELEASABLE: ReadonlySet<string> = new Set(['ALLOCATED', 'PARTLY_RELEASED']);

/**
 * The Request detail: its state, each line with its state, the Resources it is allocated on, its
 * locked Quote, why it waits or was Dropped, its waitlist position, and what it holds now, and the
 * history. Its Team's Manager may withdraw it while the state allows, and release an allocated
 * Exclusive or Metered line in whole or in part.
 */
export function RequestDetailView({ id }: { id: Id }) {
  const me = useCurrentMember();
  const detail = useRequestDetail(id);
  const withdraw = useWithdrawRequest(id);
  const reopen = useReopenRequest(id);
  const [confirmEdit, setConfirmEdit] = useState(false);
  const [releasing, setReleasing] = useState<Id | null>(null);

  if (detail.isError) return <ErrorPage error={detail.error} />;
  if (detail.isPending) return <Loading />;

  const { request, lines, history } = detail.data;
  const number = new Map(lines.map((line, index) => [line.line.id, index + 1]));
  const ownManager = me.roles.includes('MANAGER') && me.team.id === request.team.id;
  const canWithdraw = WITHDRAWABLE.has(request.state) && ownManager;
  const releasable = (line: LineDetail) =>
    ownManager && RELEASABLE.has(line.line.state) && line.line.allocationClass !== 'MATCHED';
  const released = lines.find((line) => line.line.id === releasing);

  return (
    <>
      <PageHeader
        title={`Request #${request.id}`}
        subtitle={
          <>
            {request.team.name} Team <StatusBadge status={request.state} />
            {request.submittedAt && <> Submitted {formatInstant(request.submittedAt)}</>}
            {request.windowEndsAt && <> Objection Window until {formatInstant(request.windowEndsAt)}</>}
          </>
        }
        actions={
          <>
            {request.state === 'WAITING' && ownManager && (
              <Button variant="secondary" onClick={() => setConfirmEdit(true)}>
                Edit
              </Button>
            )}
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
      {confirmEdit && (
        <section className="section panel" aria-labelledby="edit-title">
          <h2 id="edit-title" className="section-title">
            Edit this Request?
          </h2>
          <p>
            The Request goes back to Draft and loses its waitlist place. Open approvals are canceled and Quotes are
            recomputed at current prices.
          </p>
          <div className="actions">
            <Button busy={reopen.isPending} onClick={() => reopen.mutate()}>
              Return to Draft
            </Button>
            <Button variant="secondary" disabled={reopen.isPending} onClick={() => setConfirmEdit(false)}>
              Cancel
            </Button>
          </div>
          <ProblemMessage error={reopen.error} />
        </section>
      )}

      <section className="section" aria-labelledby="lines-title">
        <h2 id="lines-title" className="section-title">
          Lines
        </h2>
        <Table
          label="Lines"
          columns={[...lineColumns(number, lines), ...releaseColumns(releasable, setReleasing)]}
          rows={lines}
          rowKey={(line) => line.line.id}
          empty={<EmptyState title="This Request has no lines." />}
        />
      </section>

      <PersonLines requestId={id} lines={lines} number={number} canSelect={ownManager} />

      {released && (
        <ReleaseForm
          key={released.line.id}
          requestId={id}
          detail={released}
          number={number.get(released.line.id)}
          onClose={() => setReleasing(null)}
        />
      )}

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

function lineColumns(number: Map<Id, number>, lines: readonly LineDetail[]): Column<LineDetail>[] {
  const columns: Column<LineDetail>[] = [
    { header: '#', cell: ({ line }) => number.get(line.id), numeric: true },
    { header: 'Resource Type', cell: ({ line }) => `${line.typeName} (${formatEnum(line.allocationClass)})` },
    {
      header: 'Quantity',
      cell: ({ line }) =>
        personCount(line) ??
        amountText(line.allocationClass, line.quantity) + (line.tierName ? ` · ${line.tierName}` : ''),
      numeric: true,
    },
    { header: 'Window', cell: ({ line }) => whenText(line) },
    { header: 'Required', cell: ({ line }) => (line.required ? 'Required' : 'Optional') },
    {
      header: 'State',
      cell: ({ line, reason }) =>
        line.state === 'WAITING' && reason === 'BUMPED' ? (
          <>
            <StatusBadge status={line.state} />
            <span className="timeline__note">
              Bumped by a {line.allocationClass === 'METERED' ? 'higher-tier' : 'Priority'} line
            </span>
          </>
        ) : (
          <StatusBadge status={line.state} />
        ),
    },
    {
      header: 'Assigned',
      cell: ({ line, assignedResources }) =>
        assignedResources.length > 0
          ? assignedResources.map((resource) => resource.name).join(', ')
          : line.namedResourceName
            ? `Asked for ${line.namedResourceName}`
            : (personAsk(line) ?? '—'),
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
  ];
  // The Waitlist column shows only while some line holds a waitlist position, to keep the table narrow.
  if (lines.some((line) => line.waitlistPosition !== null)) {
    columns.push({
      header: 'Waitlist',
      cell: ({ waitlistPosition }) => (waitlistPosition === null ? '—' : `Position ${waitlistPosition}`),
    });
  }
  return columns;
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

/** What each line holds now, and a Release action on each line its Team's Manager may release. */
function releaseColumns(
  releasable: (line: LineDetail) => boolean,
  onRelease: (line: Id) => void,
): Column<LineDetail>[] {
  return [
    {
      header: 'Allocated now',
      cell: ({ line, allocatedParts }) =>
        allocatedParts.length === 0
          ? '—'
          : allocatedParts
              .map((part) => `${formatWindow(part.window)}, ${amountText(line.allocationClass, part.amount)}`)
              .join('; '),
    },
    {
      header: 'Actions',
      cell: (line) =>
        releasable(line) ? (
          <Button variant="secondary" onClick={() => onRelease(line.line.id)}>
            Release line
          </Button>
        ) : null,
    },
  ];
}
