import { useState } from 'react';
import type { AdminChange } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import { Button, EmptyState, Loading, PageHeader, Table, formatEnum, formatInstant, type Column } from '../../ui';
import { CHANGES_PAGE_SIZE, useAdminChanges } from './queries';

/**
 * /admin/changes: every change an Administrator made to Members, Teams, and roles, newest first,
 * with who made it, the value before and after, and when.
 */
export function ChangesPage() {
  const [page, setPage] = useState(0);
  const changes = useAdminChanges(page);

  if (changes.isError) return <ErrorPage error={changes.error} />;

  const columns: Column<AdminChange>[] = [
    { header: 'When', cell: (change) => formatInstant(change.at) },
    { header: 'Change', cell: (change) => formatEnum(change.kind) },
    { header: 'Member', cell: (change) => change.memberName ?? '' },
    { header: 'Team', cell: (change) => change.teamName ?? '' },
    { header: 'Old value', cell: (change) => change.oldValue ?? 'None' },
    { header: 'New value', cell: (change) => change.newValue ?? 'None' },
    { header: 'By', cell: (change) => change.actorName ?? 'System' },
  ];

  const pages = changes.data ? Math.ceil(changes.data.totalElements / CHANGES_PAGE_SIZE) : 0;
  return (
    <>
      <PageHeader title="Change record" subtitle="Every change to Members, Teams, and roles, newest first." />
      {changes.isPending ? (
        <Loading />
      ) : (
        <>
          <Table
            label="Changes"
            columns={columns}
            rows={changes.data.content}
            rowKey={(change) => change.id}
            empty={<EmptyState title="No change has been made yet." />}
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
      )}
    </>
  );
}
