import { useState, type FormEvent } from 'react';
import { fieldError } from '../../api/client';
import type { Id, MemberSummary, NewMember, Role, StoredRole, TeamOverview } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  Button,
  EmptyState,
  Field,
  Loading,
  PageHeader,
  ProblemMessage,
  StatusBadge,
  Table,
  formatEnum,
  type Column,
} from '../../ui';
import {
  ROSTER_PAGE_SIZE,
  useAdminRoster,
  useCreateMember,
  useDeactivateMember,
  useGrantRole,
  useMoveMember,
  useRevokeRole,
  useTeamOverview,
} from './queries';

const ROLE_ORDER: Role[] = ['MEMBER', 'MANAGER', 'RESOURCE_OWNER', 'PLANNER', 'ADMIN'];
const STORED_ROLES: StoredRole[] = ['MANAGER', 'RESOURCE_OWNER', 'PLANNER', 'ADMIN'];

/**
 * /admin/members: the Members of a Team. The Administrator adds a Member, moves one to another Team,
 * grants and revokes roles, and deactivates a Member, who can then no longer sign in. Granting Manager
 * replaces the Team's Manager; the server refuses what would leave a Team without one.
 */
export function MembersPage() {
  const teams = useTeamOverview();
  const [chosenTeam, setChosenTeam] = useState<Id | undefined>(undefined);
  const [page, setPage] = useState(0);

  if (teams.isError) return <ErrorPage error={teams.error} />;
  if (teams.isPending) return <Loading />;

  const activeTeams = teams.data.filter((team) => !team.retired);
  const teamId = chosenTeam ?? activeTeams[0]?.id ?? teams.data[0]?.id;

  return (
    <>
      <PageHeader title="Members" subtitle="Add Members, move them between Teams, and grant or revoke their roles." />
      <AddMemberForm teams={activeTeams} defaultTeam={teamId} />
      <section className="section" aria-labelledby="admin-roster">
        <h2 className="section-title" id="admin-roster">
          Members by Team
        </h2>
        <div className="toolbar">
          <Field label="Team">
            <select
              value={teamId ?? ''}
              onChange={(event) => {
                setChosenTeam(Number(event.target.value));
                setPage(0);
              }}
            >
              {teams.data.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.retired ? `${team.name} (retired)` : team.name}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {teamId === undefined ? (
          <EmptyState title="There are no Teams yet." />
        ) : (
          <Roster teamId={teamId} teams={activeTeams} page={page} onPage={setPage} />
        )}
      </section>
    </>
  );
}

function AddMemberForm({ teams, defaultTeam }: { teams: TeamOverview[]; defaultTeam: Id | undefined }) {
  const create = useCreateMember();
  const empty = { name: '', email: '', username: '', password: '' };
  const [form, setForm] = useState(empty);
  const [teamId, setTeamId] = useState<Id | undefined>(undefined);
  const chosenTeam = teamId ?? defaultTeam;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (chosenTeam === undefined) return;
    const member: NewMember = { ...form, teamId: chosenTeam };
    create.mutate(member, { onSuccess: () => setForm(empty) });
  }

  const set = (field: keyof typeof empty) => (event: { target: { value: string } }) =>
    setForm({ ...form, [field]: event.target.value });

  return (
    <section className="section">
      <form className="panel form" onSubmit={handleSubmit} aria-label="Add a Member">
        <Field label="Name" error={fieldError(create.error, 'name')}>
          <input value={form.name} required onChange={set('name')} />
        </Field>
        <Field label="Email" error={fieldError(create.error, 'email')}>
          <input type="email" value={form.email} required onChange={set('email')} />
        </Field>
        <Field label="Username" error={fieldError(create.error, 'username')}>
          <input value={form.username} required autoComplete="off" onChange={set('username')} />
        </Field>
        <Field
          label="Initial password"
          hint="8 to 72 characters. The Member signs in with it."
          error={fieldError(create.error, 'password')}
        >
          <input
            type="password"
            value={form.password}
            required
            minLength={8}
            maxLength={72}
            autoComplete="new-password"
            onChange={set('password')}
          />
        </Field>
        <Field label="Team of the new Member">
          <select value={chosenTeam ?? ''} required onChange={(event) => setTeamId(Number(event.target.value))}>
            {teams.map((team) => (
              <option key={team.id} value={team.id}>
                {team.name}
              </option>
            ))}
          </select>
        </Field>
        <ProblemMessage error={create.error} />
        <div className="actions">
          <Button type="submit" busy={create.isPending}>
            Add Member
          </Button>
        </div>
      </form>
    </section>
  );
}

interface RosterProps {
  teamId: Id;
  teams: TeamOverview[];
  page: number;
  onPage: (page: number) => void;
}

function Roster({ teamId, teams, page, onPage }: RosterProps) {
  const roster = useAdminRoster(teamId, page);
  const deactivate = useDeactivateMember();
  const move = useMoveMember();
  const grant = useGrantRole();
  const revoke = useRevokeRole();
  const mutations = [deactivate, move, grant, revoke];
  const [moveTo, setMoveTo] = useState<Record<Id, Id>>({});
  const [toGrant, setToGrant] = useState<Record<Id, StoredRole>>({});

  /** Clears the last refusal, so the message shown is always the latest action's. */
  function clear() {
    mutations.forEach((mutation) => mutation.reset());
  }

  if (roster.isError) return <ProblemMessage error={roster.error} />;
  if (roster.isPending) return <Loading />;

  const columns: Column<MemberSummary>[] = [
    {
      header: 'Member',
      cell: (member) => (
        <>
          {member.name} <span className="field__hint">{member.username}</span>
        </>
      ),
    },
    { header: 'Roles', cell: (member) => rolesText(member.roles) },
    {
      header: 'Status',
      cell: (member) =>
        member.active ? <StatusBadge status="ACTIVE" tone="success" /> : <StatusBadge status="INACTIVE" />,
    },
    {
      header: 'Action',
      cell: (member) => {
        if (!member.active) return null;
        const targets = teams.filter((team) => team.id !== member.team.id);
        const target = moveTo[member.id] ?? targets[0]?.id;
        const grantable = STORED_ROLES.filter((role) => !member.roles.includes(role));
        const role = toGrant[member.id] ?? grantable[0];
        const revocable = STORED_ROLES.filter((held) => held !== 'MANAGER' && member.roles.includes(held));
        return (
          <div className="admin-actions">
            {targets.length > 0 && target !== undefined && (
              <div className="line-actions">
                <select
                  aria-label={`Team for ${member.name}`}
                  value={target}
                  onChange={(event) => setMoveTo({ ...moveTo, [member.id]: Number(event.target.value) })}
                >
                  {targets.map((team) => (
                    <option key={team.id} value={team.id}>
                      {team.name}
                    </option>
                  ))}
                </select>
                <Button
                  variant="secondary"
                  busy={move.isPending && move.variables?.id === member.id}
                  onClick={() => {
                    clear();
                    move.mutate({ id: member.id, teamId: target });
                  }}
                >
                  Move {member.name}
                </Button>
              </div>
            )}
            {role !== undefined && (
              <div className="line-actions">
                <select
                  aria-label={`Role for ${member.name}`}
                  value={role}
                  onChange={(event) =>
                    setToGrant({ ...toGrant, [member.id]: event.target.value as StoredRole })
                  }
                >
                  {grantable.map((option) => (
                    <option key={option} value={option}>
                      {formatEnum(option)}
                    </option>
                  ))}
                </select>
                <Button
                  variant="secondary"
                  busy={grant.isPending && grant.variables?.memberId === member.id}
                  onClick={() => {
                    clear();
                    grant.mutate({ memberId: member.id, role });
                  }}
                >
                  Grant role to {member.name}
                </Button>
              </div>
            )}
            <div className="line-actions">
              {revocable.map((held) => (
                <Button
                  key={held}
                  variant="secondary"
                  busy={revoke.isPending && revoke.variables?.memberId === member.id && revoke.variables.role === held}
                  onClick={() => {
                    clear();
                    revoke.mutate({ memberId: member.id, role: held });
                  }}
                >
                  Revoke {formatEnum(held)} from {member.name}
                </Button>
              ))}
              <Button
                variant="danger"
                busy={deactivate.isPending && deactivate.variables === member.id}
                onClick={() => {
                  clear();
                  deactivate.mutate(member.id);
                }}
              >
                Deactivate {member.name}
              </Button>
            </div>
          </div>
        );
      },
    },
  ];

  const pages = Math.ceil(roster.data.totalElements / ROSTER_PAGE_SIZE);
  return (
    <>
      {mutations.map((mutation, index) => (
        <ProblemMessage key={index} error={mutation.error} />
      ))}
      <Table
        label="Members"
        columns={columns}
        rows={roster.data.content}
        rowKey={(member) => member.id}
        empty={<EmptyState title="This Team has no Members yet." />}
      />
      {pages > 1 && (
        <div className="pager">
          <Button variant="secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
            Previous
          </Button>
          <span>
            Page {page + 1} of {pages}
          </span>
          <Button variant="secondary" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
            Next
          </Button>
        </div>
      )}
    </>
  );
}

/** The roles a Member holds, Member first: "Member, Manager". */
function rolesText(roles: Role[]): string {
  return ROLE_ORDER.filter((role) => roles.includes(role))
    .map(formatEnum)
    .join(', ');
}
