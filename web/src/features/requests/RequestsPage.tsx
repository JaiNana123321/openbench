import { Link, useNavigate, useSearchParams } from 'react-router';
import type { RequestSummary } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { hasAnyRole } from '../../auth/roles';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  Button,
  EmptyState,
  formatCredits,
  formatInstant,
  Loading,
  PageHeader,
  ProblemMessage,
  StatusBadge,
  Table,
  type Column,
} from '../../ui';
import { REQUEST_PAGE_SIZE, useCreateRequest, useRequests } from './queries';

const columns: Column<RequestSummary>[] = [
  { header: 'Request', cell: (request) => <Link to={`/requests/${request.id}`}>Request #{request.id}</Link> },
  { header: 'Team', cell: (request) => request.team.name },
  { header: 'State', cell: (request) => <StatusBadge status={request.state} /> },
  { header: 'Created', cell: (request) => formatInstant(request.createdAt) },
  { header: 'Lines', cell: (request) => request.lineCount, numeric: true },
  { header: 'Required', cell: (request) => formatCredits(request.requiredSum), numeric: true },
];

/**
 * /requests: the Team's Requests, newest first, with their state and the Required lines' Quotes.
 * A Manager starts a new Draft here; a Capacity Planner sees every Team's.
 */
export function RequestsPage() {
  const me = useCurrentMember();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? 0);
  const requests = useRequests(page);
  const create = useCreateRequest();
  const isManager = hasAnyRole(me, ['MANAGER']);

  if (requests.isError) return <ErrorPage error={requests.error} />;

  function newRequest() {
    create.mutate(undefined, { onSuccess: (request) => navigate(`/requests/${request.id}`) });
  }

  const pages = requests.data ? Math.ceil(requests.data.totalElements / REQUEST_PAGE_SIZE) : 0;
  return (
    <>
      <PageHeader
        title="Requests"
        subtitle={isManager ? `${me.team.name} Team’s Requests.` : 'Every Team’s Requests.'}
        actions={
          isManager && (
            <Button onClick={newRequest} busy={create.isPending}>
              New Request
            </Button>
          )
        }
      />
      <ProblemMessage error={create.error} />
      {requests.isPending ? (
        <Loading />
      ) : (
        <>
          <Table
            label="Requests"
            columns={columns}
            rows={requests.data.content}
            rowKey={(row) => row.id}
            empty={<EmptyState title="No Requests yet." />}
          />
          {pages > 1 && (
            <div className="pager">
              <Button variant="secondary" disabled={page === 0} onClick={() => setParams({ page: String(page - 1) })}>
                Previous
              </Button>
              <span>
                Page {page + 1} of {pages}
              </span>
              <Button
                variant="secondary"
                disabled={page + 1 >= pages}
                onClick={() => setParams({ page: String(page + 1) })}
              >
                Next
              </Button>
            </div>
          )}
        </>
      )}
    </>
  );
}
