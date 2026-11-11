import { useState } from 'react';
import type { ApprovalView, Instant } from '../../api/types';
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
  Table,
  type Column,
} from '../../ui';
import { useApprovals, useDecide } from './queries';

/**
 * Approvals (Matched.Approve.Route): the requests to lend the Manager's own Members, each with the
 * requesting Team, the days, the Earning, and the time left in the Objection Window. The Manager
 * approves or refuses each with a reason; a decided one leaves the list.
 */
export function ApprovalsPage() {
  const approvals = useApprovals();

  if (approvals.isError) return <ErrorPage error={approvals.error} />;
  if (approvals.isPending) return <Loading />;

  const columns: Column<ApprovalView>[] = [
    { header: 'Candidate', cell: (approval) => approval.candidateName },
    { header: 'Requesting Team', cell: (approval) => approval.requestingTeam.name },
    { header: 'Days', cell: (approval) => formatDayRange(approval.days) },
    { header: 'Earning', cell: (approval) => formatCredits(approval.earning), numeric: true },
    { header: 'Time left', cell: (approval) => timeLeft(approval.windowEndsAt) },
    { header: 'Decision', cell: (approval) => <DecisionForm approval={approval} /> },
  ];
  return (
    <>
      <PageHeader title="Approvals" subtitle="Requests to lend your Team’s Members to other Teams." />
      <Table
        label="Approvals"
        columns={columns}
        rows={approvals.data}
        rowKey={(approval) => approval.id}
        empty={<EmptyState title="No approval awaits your decision." />}
      />
    </>
  );
}

/** A reason and the two decisions; a refusal the server rejects shows its sentence. */
function DecisionForm({ approval }: { approval: ApprovalView }) {
  const decide = useDecide();
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

/** "1 day 3 h left", "45 min left", or "Lapsed"; the window's end is shown on hover. */
function timeLeft(windowEndsAt: Instant | null) {
  if (windowEndsAt === null) return '—';
  const minutes = Math.floor((new Date(windowEndsAt).getTime() - Date.now()) / 60_000);
  let text: string;
  if (minutes <= 0) {
    text = 'Lapsed';
  } else {
    const days = Math.floor(minutes / (24 * 60));
    const hours = Math.floor((minutes % (24 * 60)) / 60);
    const parts = [
      days > 0 ? `${days} ${days === 1 ? 'day' : 'days'}` : '',
      hours > 0 ? `${hours} h` : '',
      days === 0 && hours === 0 ? `${minutes} min` : '',
    ].filter(Boolean);
    text = `${parts.join(' ')} left`;
  }
  return <span title={`Until ${formatInstant(windowEndsAt)}`}>{text}</span>;
}
