import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import type { DraftView } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { ErrorPage } from '../../shell/ErrorPage';
import { Loading, PageHeader, StatusBadge } from '../../ui';
import { AddLineForm } from './AddLineForm';
import { LinesTable } from './LinesTable';
import { TotalsPanel } from './TotalsPanel';
import { useDraft } from './queries';

/**
 * /requests/:id. A Draft opens in the Draft editor for its Team's Manager; any other Request, or a
 * Draft seen by a Capacity Planner, shows a read-only summary of its lines and totals.
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
  return editable ? <DraftEditor view={view} /> : <RequestSummaryView view={view} />;
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

/** A Request that is not an editable Draft: its lines and totals, read only. */
function RequestSummaryView({ view }: { view: DraftView }) {
  return (
    <>
      <RequestHeader view={view} />
      <div className="draft-layout">
        <LinesTable requestId={view.request.id} lines={view.lines} editable={false} />
        <TotalsPanel view={view} />
      </div>
    </>
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
