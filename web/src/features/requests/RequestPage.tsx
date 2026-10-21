import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { isApiError } from '../../api/client';
import type { DraftView } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { ErrorPage } from '../../shell/ErrorPage';
import { Button, formatCredits, Loading, PageHeader, ProblemMessage, StatusBadge } from '../../ui';
import { AddLineForm } from './AddLineForm';
import { LinesTable } from './LinesTable';
import { RequestDetailView } from './RequestDetailView';
import { TotalsPanel } from './TotalsPanel';
import { useDraft, useSubmitRequest } from './queries';

/**
 * /requests/:id. A Draft opens in the Draft editor, with Submit, for its Team's Manager; any other
 * Request, or a Draft seen by a Capacity Planner, shows the Request detail. A submission that
 * succeeds refreshes the Request, which then shows its detail.
 */
export function RequestPage() {
  const id = Number(useParams().id);
  const me = useCurrentMember();
  const draft = useDraft(id);

  if (draft.isError) return <ErrorPage error={draft.error} />;
  if (draft.isPending) return <Loading />;

  const view = draft.data;
  const editable =
    view.request.state === 'DRAFT' && me.roles.includes('MANAGER') && me.team.id === view.request.team.id;
  return editable ? (
    <DraftEditor view={view} totalsActions={<SubmitAction view={view} />} />
  ) : (
    <RequestDetailView id={view.request.id} />
  );
}

interface DraftEditorProps {
  view: DraftView;
  /** Actions under the totals, such as the Submit button. */
  totalsActions?: ReactNode;
}

/** The Draft editor: the add-line form, the lines with their Quotes and actions, and the totals. */
export function DraftEditor({ view, totalsActions }: DraftEditorProps) {
  const { request } = view;
  return (
    <>
      <RequestHeader view={view} />
      <div className="draft-layout">
        <div>
          <AddLineForm requestId={request.id} />
          <LinesTable requestId={request.id} lines={view.lines} editable />
        </div>
        <TotalsPanel view={view}>{totalsActions}</TotalsPanel>
      </div>
    </>
  );
}

/**
 * Submit: locks the Quotes and allocates the Request or puts it on the waitlist. A refusal shows the
 * server's sentence, and for INSUFFICIENT_BALANCE the shortfall it reports.
 */
function SubmitAction({ view }: { view: DraftView }) {
  const submit = useSubmitRequest(view.request.id);
  const shortfall =
    isApiError(submit.error) && submit.error.code === 'INSUFFICIENT_BALANCE'
      ? Number(submit.error.properties.shortfall)
      : undefined;
  return (
    <div className="submit-action">
      <Button busy={submit.isPending} disabled={view.lines.length === 0} onClick={() => submit.mutate()}>
        Submit
      </Button>
      <ProblemMessage
        error={submit.error}
        title={shortfall === undefined ? undefined : `Refused: short by ${formatCredits(shortfall)} credits`}
      />
    </div>
  );
}

function RequestHeader({ view }: { view: DraftView }) {
  const { request } = view;
  return (
    <PageHeader
      title={`Request #${request.id}`}
      subtitle={
        <>
          {request.team.name} Team <StatusBadge status={request.state} />
        </>
      }
      actions={
        <Link to="/requests" className="button button--secondary">
          All Requests
        </Link>
      }
    />
  );
}
