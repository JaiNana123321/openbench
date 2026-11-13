import { useState } from 'react';
import { Link } from 'react-router';
import type { EscalatedApproval, EscalatedRequest, Instant } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  Button,
  EmptyState,
  formatCredits,
  formatDayRange,
  formatInstant,
  Loading,
  PageHeader,
  ProblemMessage,
  StatusBadge,
  Table,
  type Column,
} from '../../ui';
import { useDecideEscalated, useEscalations } from './queries';

/**
 * Escalations (Matched.Escalate): the Requests whose Objection Window lapsed with approvals
 * outstanding, the longest lapsed first, each with its Candidates, the silent Manager, and the time
 * since the lapse. The Capacity Planner approves or refuses each for the silent Manager, with a
 * reason; a decided one leaves the list.
 */
export function EscalationsPage() {
  const escalations = useEscalations();

  if (escalations.isError) return <ErrorPage error={escalations.error} />;
  if (escalations.isPending) return <Loading />;

  return (
    <>
      <PageHeader
        title="Escalations"
        subtitle="Approvals left outstanding past the Objection Window. Decide them for the silent Manager."
      />
      {escalations.data.length === 0 ? (
        <EmptyState title="No approval is escalated." />
      ) : (
        escalations.data.map((request) => <EscalatedRequestPanel key={request.id} request={request} />)
      )}
    </>
  );
}

const columns: Column<EscalatedApproval>[] = [
  { header: 'Candidate', cell: (approval) => approval.candidateName },
  { header: 'Lent by', cell: (approval) => approval.lendingTeam.name },
  { header: 'Silent Manager', cell: (approval) => approval.silentManagerName },
  { header: 'Days', cell: (approval) => formatDayRange(approval.days) },
  { header: 'Line', cell: (approval) => (approval.required ? 'Required' : 'Optional') },
  { header: 'Earning', cell: (approval) => formatCredits(approval.earning), numeric: true },
  { header: 'Decision', cell: (approval) => <DecisionForm approval={approval} /> },
];

function EscalatedRequestPanel({ request }: { request: EscalatedRequest }) {
  return (
    <section className="panel section" aria-label={`Request #${request.id}, ${request.team.name}`}>
      <h2 className="section-title">
        <Link to={`/requests/${request.id}`}>Request #{request.id}</Link>, {request.team.name}{' '}
        <StatusBadge status={request.state} />
      </h2>
      {request.lapsedAt && (
        <p className="escalation-lapse">
          The Objection Window lapsed {formatInstant(request.lapsedAt)}, {sinceText(request.lapsedAt)}.
        </p>
      )}
      <Table
        label={`Escalated approvals of Request ${request.id}`}
        columns={columns}
        rows={request.approvals}
        rowKey={(approval) => approval.id}
      />
    </section>
  );
}

/** A reason and the two decisions; a decision the server rejects shows its sentence. */
function DecisionForm({ approval }: { approval: EscalatedApproval }) {
  const decide = useDecideEscalated();
  const [reason, setReason] = useState('');
  return (
    <div className="decision-form">
      <input
        type="text"
        aria-label={`Reason for ${approval.candidateName}`}
        placeholder="Reason"
        value={reason}
        maxLength={1000}
        onChange={(event) => setReason(event.target.value)}
      />
      <Button
        busy={decide.isPending && decide.variables?.decision === 'APPROVE'}
        disabled={decide.isPending}
        onClick={() => decide.mutate({ id: approval.id, decision: 'APPROVE', reason })}
      >
        Approve
      </Button>
      <Button
        variant="danger"
        busy={decide.isPending && decide.variables?.decision === 'REFUSE'}
        disabled={decide.isPending}
        onClick={() => decide.mutate({ id: approval.id, decision: 'REFUSE', reason })}
      >
        Refuse
      </Button>
      <ProblemMessage error={decide.error} />
    </div>
  );
}

/** "2 days 3 h ago", "45 min ago", or "just now": the time since the lapse. */
function sinceText(lapsedAt: Instant): string {
  const minutes = Math.floor((Date.now() - new Date(lapsedAt).getTime()) / 60_000);
  if (minutes < 1) return 'just now';
  const days = Math.floor(minutes / (24 * 60));
  const hours = Math.floor((minutes % (24 * 60)) / 60);
  const parts = [
    days > 0 ? `${days} ${days === 1 ? 'day' : 'days'}` : '',
    hours > 0 ? `${hours} h` : '',
    days === 0 && hours === 0 ? `${minutes} min` : '',
  ].filter(Boolean);
  return `${parts.join(' ')} ago`;
}
