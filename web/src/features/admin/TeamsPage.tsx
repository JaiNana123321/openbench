import { useState, type FormEvent } from 'react';
import { fieldError } from '../../api/client';
import type { TeamOverview } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import { Button, EmptyState, Field, Loading, PageHeader, ProblemMessage, StatusBadge, Table, type Column } from '../../ui';
import { useCreateTeam, useRenameTeam, useRetireTeam, useTeamOverview } from './queries';

/**
 * /admin/teams: every Team with its Manager and active Members. The Administrator adds, renames, and
 * retires Teams; a Team with active Members cannot be retired, and a retired Team's history stays.
 */
export function TeamsPage() {
  const teams = useTeamOverview();
  const create = useCreateTeam();
  const rename = useRenameTeam();
  const retire = useRetireTeam();
  const [name, setName] = useState('');
  const [renaming, setRenaming] = useState<{ id: number; name: string } | null>(null);

  if (teams.isError) return <ErrorPage error={teams.error} />;

  function handleCreate(event: FormEvent) {
    event.preventDefault();
    create.mutate(name, { onSuccess: () => setName('') });
  }

  function handleRename(event: FormEvent) {
    event.preventDefault();
    if (renaming) rename.mutate(renaming, { onSuccess: () => setRenaming(null) });
  }

  const columns: Column<TeamOverview>[] = [
    {
      header: 'Team',
      cell: (team) =>
        renaming?.id === team.id ? (
          <form className="line-actions" onSubmit={handleRename} aria-label={`Rename ${team.name}`}>
            <input
              aria-label={`New name for ${team.name}`}
              value={renaming.name}
              required
              onChange={(event) => setRenaming({ id: team.id, name: event.target.value })}
            />
            <Button type="submit" busy={rename.isPending}>
              Save name
            </Button>
            <Button variant="secondary" onClick={() => setRenaming(null)}>
              Cancel
            </Button>
          </form>
        ) : (
          team.name
        ),
    },
    { header: 'Manager', cell: (team) => team.managerName ?? 'None' },
    { header: 'Active Members', cell: (team) => team.activeMembers, numeric: true },
    {
      header: 'Status',
      cell: (team) => (team.retired ? <StatusBadge status="RETIRED" /> : <StatusBadge status="ACTIVE" tone="success" />),
    },
    {
      header: 'Action',
      cell: (team) =>
        !team.retired && (
          <div className="line-actions">
            <Button
              variant="secondary"
              onClick={() => {
                rename.reset();
                setRenaming({ id: team.id, name: team.name });
              }}
            >
              Rename {team.name}
            </Button>
            <Button
              variant="danger"
              busy={retire.isPending && retire.variables === team.id}
              onClick={() => retire.mutate(team.id)}
            >
              Retire {team.name}
            </Button>
          </div>
        ),
    },
  ];

  return (
    <>
      <PageHeader title="Teams" subtitle="Each Team has exactly one Manager, granted on the Members page." />
      <section className="section">
        <form className="panel form" onSubmit={handleCreate} aria-label="Add a Team">
          <Field label="Name" error={fieldError(create.error, 'name')}>
            <input value={name} required onChange={(event) => setName(event.target.value)} />
          </Field>
          <ProblemMessage error={create.error} />
          <div className="actions">
            <Button type="submit" busy={create.isPending}>
              Add Team
            </Button>
          </div>
        </form>
      </section>
      <ProblemMessage error={rename.error} />
      <ProblemMessage error={retire.error} />
      {teams.isPending ? (
        <Loading />
      ) : (
        <Table
          label="Teams"
          columns={columns}
          rows={teams.data}
          rowKey={(team) => team.id}
          empty={<EmptyState title="There are no Teams yet." />}
        />
      )}
    </>
  );
}
