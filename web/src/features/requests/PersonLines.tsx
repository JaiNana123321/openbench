import { useState } from 'react';
import type { CandidateApproval, Id, LineDetail, Loan, ShortlistEntry } from '../../api/types';
import {
  Button,
  EmptyState,
  formatDayRange,
  formatInstant,
  ProblemMessage,
  StatusBadge,
  Table,
  type Column,
} from '../../ui';
import { canEndEarly, EndLoanForm } from './EndLoanForm';
import { personAsk, personCount, whenText } from './personLineText';
import { useSelectCandidates, useShortlist } from './queries';

interface PersonLinesProps {
  requestId: Id;
  lines: LineDetail[];
  /** Each line's number on the Request, from 1. */
  number: Map<Id, number>;
  /** Whether the caller is the Request's Manager, who selects Candidates. */
  canSelect: boolean;
}

/**
 * The Request's Person lines: each line's Candidates with their approvals and, while the line awaits
 * Candidates, its Shortlist. The Manager selects exactly the Candidates still missing; the system
 * selects no one. A named Person is selected at submission.
 */
export function PersonLines({ requestId, lines, number, canSelect }: PersonLinesProps) {
  const people = lines.filter((line) => line.line.allocationClass === 'MATCHED');
  if (people.length === 0) return null;
  return (
    <section className="section" aria-labelledby="people-title">
      <h2 id="people-title" className="section-title">
        People
      </h2>
      {people.map((line) => (
        <PersonLine
          key={line.line.id}
          requestId={requestId}
          detail={line}
          number={number.get(line.line.id)}
          canSelect={canSelect}
        />
      ))}
    </section>
  );
}

const LIVE: ReadonlySet<string> = new Set(['OUTSTANDING', 'APPROVED']);
const OPEN: ReadonlySet<string> = new Set(['AWAITING_APPROVAL', 'WAITING', 'REFUSED']);

interface PersonLineProps {
  requestId: Id;
  detail: LineDetail;
  number: number | undefined;
  canSelect: boolean;
}

function PersonLine({ requestId, detail, number, canSelect }: PersonLineProps) {
  const { line } = detail;
  const candidates = detail.candidates ?? [];
  const missing = line.quantity - candidates.filter((candidate) => LIVE.has(candidate.state)).length;
  const bySkills = !line.namedMember;
  const open = OPEN.has(line.state);
  const picking = canSelect && open && bySkills && missing > 0;
  const shortlist = useShortlist(requestId, line.id, bySkills && open);
  const select = useSelectCandidates(requestId);
  const [picked, setPicked] = useState<Id[]>([]);
  const [ending, setEnding] = useState<Loan | undefined>();
  const selectedBefore = new Set(candidates.map((candidate) => candidate.member));

  function toggle(member: Id) {
    setPicked((current) => (current.includes(member) ? current.filter((id) => id !== member) : [...current, member]));
  }

  const shortlistColumns: Column<ShortlistEntry>[] = [
    ...(picking
      ? [
          {
            header: 'Select',
            cell: (entry: ShortlistEntry) => (
              <input
                type="checkbox"
                aria-label={`Select ${entry.name}`}
                checked={picked.includes(entry.member)}
                disabled={selectedBefore.has(entry.member)}
                onChange={() => toggle(entry.member)}
              />
            ),
          },
        ]
      : []),
    { header: '#', cell: (entry) => entry.position, numeric: true },
    { header: 'Name', cell: (entry) => entry.name },
    { header: 'Team', cell: (entry) => entry.team.name },
    { header: 'Skills', cell: (entry) => entry.skills.join(', ') || '—' },
    { header: 'Person-days lent', cell: (entry) => entry.personDaysLent, numeric: true },
  ];

  const title = `Line ${number ?? line.id}: ${personAsk(line)}, ${personCount(line)}, ${whenText(line)}`;
  return (
    <div className="panel section" aria-label={title}>
      <h3 className="section-title">{title}</h3>
      {line.state === 'REFUSED' && (
        <p className="replacement-needed" role="status">
          Replacement needed: a Candidate was refused.{' '}
          {canSelect ? 'Select a replacement from the Shortlist.' : 'The Manager selects a replacement.'}
        </p>
      )}
      <Table
        label={`Candidates of line ${number ?? line.id}`}
        columns={candidateColumns}
        rows={candidates}
        rowKey={(candidate) => candidate.approval}
        empty={<EmptyState title={bySkills ? 'No Candidate is selected yet.' : 'No approval is requested yet.'} />}
      />
      {(detail.loans ?? []).length > 0 && (
        <>
          <h4>Loans</h4>
          <Table
            label={`Loans of line ${number ?? line.id}`}
            columns={loanColumns(canSelect ? setEnding : undefined)}
            rows={detail.loans ?? []}
            rowKey={(loan) => loan.id}
          />
          {ending && <EndLoanForm key={ending.id} loan={ending} onClose={() => setEnding(undefined)} />}
        </>
      )}
      {bySkills && open && (
        <>
          <h4>Shortlist</h4>
          {shortlist.isError ? (
            <ProblemMessage error={shortlist.error} />
          ) : (
            <Table
              label={`Shortlist of line ${number ?? line.id}`}
              columns={shortlistColumns}
              rows={shortlist.data?.candidates ?? []}
              rowKey={(entry) => entry.member}
              empty={<EmptyState title={shortlist.isPending ? 'Loading the Shortlist…' : 'No Member is eligible.'} />}
            />
          )}
          {picking && (
            <div className="actions">
              <Button
                busy={select.isPending}
                disabled={picked.length !== missing}
                onClick={() =>
                  select.mutate({ lineId: line.id, members: picked }, { onSuccess: () => setPicked([]) })
                }
              >
                Select {missing} {missing === 1 ? 'Candidate' : 'Candidates'}
              </Button>
              <ProblemMessage error={select.error} />
            </div>
          )}
        </>
      )}
    </div>
  );
}

const candidateColumns: Column<CandidateApproval>[] = [
  { header: 'Candidate', cell: (candidate) => candidate.name },
  { header: 'Team', cell: (candidate) => candidate.team.name },
  {
    header: 'Approval',
    cell: (candidate) => (
      <>
        <StatusBadge status={candidate.state} />
        {candidate.escalated && <span className="timeline__note">Escalated</span>}
      </>
    ),
  },
  {
    header: 'Decided',
    cell: (candidate) =>
      candidate.decidedAt ? `${candidate.decidedByName ?? 'System'}, ${formatInstant(candidate.decidedAt)}` : '—',
  },
  { header: 'Reason', cell: (candidate) => candidate.reason || '—' },
];

/**
 * The Loans a Person line formed once its Request was allocated. The borrowing Manager (given
 * `onEnd`) may end one early while a day before its last day remains.
 */
function loanColumns(onEnd: ((loan: Loan) => void) | undefined): Column<Loan>[] {
  return [
    { header: 'Person', cell: (loan) => loan.memberName },
    { header: 'Lent by', cell: (loan) => loan.lendingTeam.name },
    {
      header: 'Days',
      cell: (loan) => (
        <>
          {formatDayRange(loan.days)}
          {loan.endedEarly && <span className="timeline__note">Ended early</span>}
        </>
      ),
    },
    { header: 'Days lent', cell: (loan) => loan.daysLent, numeric: true },
    ...(onEnd
      ? [
          {
            header: 'Action',
            cell: (loan: Loan) =>
              canEndEarly(loan) ? (
                <Button variant="secondary" onClick={() => onEnd(loan)}>
                  End Loan early…
                </Button>
              ) : (
                '—'
              ),
          },
        ]
      : []),
  ];
}
