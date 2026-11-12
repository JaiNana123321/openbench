import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { MemberSummary, Page, TeamOverview } from '../../api/types';
import { ben, carol } from '../../test/members';
import { json, mockApi, problem, session, type MockReply, type MockRequest } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const platform: TeamOverview = {
  id: 1,
  name: 'Platform',
  retired: false,
  manager: 1,
  managerName: 'Alice Chen',
  activeMembers: 2,
};
const operations: TeamOverview = {
  id: 3,
  name: 'Operations',
  retired: false,
  manager: 5,
  managerName: 'Oscar Reed',
  activeMembers: 4,
};
const design: TeamOverview = {
  id: 9,
  name: 'Product Design',
  retired: true,
  manager: null,
  managerName: null,
  activeMembers: 0,
};

const alice: MemberSummary = {
  id: 1,
  name: 'Alice Chen',
  username: 'alice',
  team: { id: 1, name: 'Platform' },
  active: true,
  roles: ['MANAGER', 'MEMBER'],
};
const benRow: MemberSummary = {
  id: 2,
  name: 'Ben Ortiz',
  username: 'ben',
  team: { id: 1, name: 'Platform' },
  active: true,
  roles: ['MEMBER'],
};
const dave: MemberSummary = {
  id: 7,
  name: 'Dave Kim',
  username: 'dave',
  team: { id: 3, name: 'Operations' },
  active: true,
  roles: ['RESOURCE_OWNER', 'MEMBER'],
};
const nina: MemberSummary = {
  id: 12,
  name: 'Nina Lowe',
  username: 'nina',
  team: { id: 3, name: 'Operations' },
  active: false,
  roles: ['MEMBER'],
};

function page<T>(content: T[]): Page<T> {
  return { content, page: 0, size: 50, totalElements: content.length };
}

const rosters: Record<string, MemberSummary[]> = { '1': [alice, benRow], '3': [dave, nina], '9': [] };

/** The first call to the route, once the screen has made it. */
async function firstCall(server: ReturnType<typeof mockApi>, route: string): Promise<MockRequest> {
  await waitFor(() => expect(server.callsTo(route)).not.toHaveLength(0));
  return server.callsTo(route)[0];
}

function serve(routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(carol),
    'GET /api/admin/teams': json([platform, operations, design]),
    'GET /api/teams/:id/members': (request: MockRequest) => json(page(rosters[request.params.id] ?? [])),
    ...routes,
  });
}

describe('Members administration', () => {
  it('lists the Members of a Team with their roles and status', async () => {
    const server = serve();
    const { user } = renderApp('/admin/members');

    const table = await screen.findByRole('table', { name: 'Members' });
    expect(within(table).getByRole('row', { name: /Alice Chen/ })).toHaveTextContent('Member, Manager');
    expect(server.callsTo('GET /api/teams/:id/members')[0].params.id).toBe('1');

    await user.selectOptions(screen.getByLabelText('Team'), 'Operations');

    expect(await screen.findByRole('row', { name: /Nina Lowe/ })).toHaveTextContent('Inactive');
    const daveRow = screen.getByRole('row', { name: /Dave Kim/ });
    expect(daveRow).toHaveTextContent('Member, Resource owner');
    expect(daveRow).toHaveTextContent('Active');
    // A deactivated Member is shown but offers no action.
    expect(screen.queryByRole('button', { name: 'Deactivate Nina Lowe' })).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Product Design (retired)' })).toBeInTheDocument();
  });

  it('adds a Member to a Team (adm-member, step 1)', async () => {
    const server = serve({ 'POST /api/admin/members': json({ ...nina, active: true }, 201) });
    const { user } = renderApp('/admin/members');

    await user.type(await screen.findByLabelText('Name'), 'Nina Lowe');
    await user.type(screen.getByLabelText('Email'), 'nina@openbench.example');
    await user.type(screen.getByLabelText('Username'), 'nina');
    await user.type(screen.getByLabelText(/Initial password/), 'start-2026');
    await user.selectOptions(screen.getByLabelText('Team of the new Member'), 'Operations');
    await user.click(screen.getByRole('button', { name: 'Add Member' }));

    expect(JSON.parse((await firstCall(server, 'POST /api/admin/members')).body ?? '')).toEqual({
      name: 'Nina Lowe',
      email: 'nina@openbench.example',
      username: 'nina',
      password: 'start-2026',
      teamId: 3,
    });
    expect(await screen.findByLabelText('Username')).toHaveValue('');
    // The retired Team takes no new Member.
    const teamChoice = screen.getByLabelText('Team of the new Member');
    expect(within(teamChoice).queryByRole('option', { name: 'Product Design' })).not.toBeInTheDocument();
  });

  it('shows the refusal of a taken username', async () => {
    serve({ 'POST /api/admin/members': problem(422, 'USERNAME_TAKEN', 'The username nina is already taken.') });
    const { user } = renderApp('/admin/members');

    await user.type(await screen.findByLabelText('Name'), 'Nina Lowe');
    await user.type(screen.getByLabelText('Email'), 'nina@openbench.example');
    await user.type(screen.getByLabelText('Username'), 'nina');
    await user.type(screen.getByLabelText(/Initial password/), 'start-2026');
    await user.click(screen.getByRole('button', { name: 'Add Member' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The username nina is already taken.');
  });

  it('deactivates a Member, and shows the refusal for a Manager who is not the last Member', async () => {
    const server = serve({
      'POST /api/admin/members/:id/deactivate': (request: MockRequest) =>
        request.params.id === '1'
          ? problem(
              422,
              'TEAM_NEEDS_MANAGER',
              'The Team’s Manager can be deactivated only as its last Member; grant the role to a successor first.',
            )
          : json({ ...benRow, active: false }),
    });
    const { user } = renderApp('/admin/members');

    await user.click(await screen.findByRole('button', { name: 'Deactivate Ben Ortiz' }));
    expect((await firstCall(server, 'POST /api/admin/members/:id/deactivate')).params.id).toBe('2');

    await user.click(screen.getByRole('button', { name: 'Deactivate Alice Chen' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('grant the role to a successor first');
  });

  it('moves a Member to another Team', async () => {
    const server = serve({ 'POST /api/admin/members/:id/move': json({ ...benRow, team: { id: 3, name: 'Operations' } }) });
    const { user } = renderApp('/admin/members');

    await user.selectOptions(await screen.findByLabelText('Team for Ben Ortiz'), 'Operations');
    await user.click(screen.getByRole('button', { name: 'Move Ben Ortiz' }));

    const call = await firstCall(server, 'POST /api/admin/members/:id/move');
    expect(call.params.id).toBe('2');
    expect(JSON.parse(call.body ?? '')).toEqual({ teamId: 3 });
    // The retired Team is no target.
    const targets = screen.getByLabelText('Team for Ben Ortiz');
    expect(within(targets).queryByRole('option', { name: /Product Design/ })).not.toBeInTheDocument();
  });

  it('grants Manager and revokes Resource Owner (adm-teams-roles, steps 2 and 4)', async () => {
    const server = serve({
      'POST /api/admin/roles': json({ ...benRow, roles: ['MEMBER', 'MANAGER'] }),
      'DELETE /api/admin/roles/:memberId/:role': json({ ...dave, roles: ['MEMBER'] }),
    });
    const { user } = renderApp('/admin/members');

    // Alice already holds Manager, so it is not offered to her; Ben may be granted it.
    const aliceRoles = await screen.findByLabelText('Role for Alice Chen');
    expect(within(aliceRoles).queryByRole('option', { name: 'Manager' })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Role for Ben Ortiz'), 'Manager');
    await user.click(screen.getByRole('button', { name: 'Grant role to Ben Ortiz' }));
    expect(JSON.parse((await firstCall(server, 'POST /api/admin/roles')).body ?? '')).toEqual({ memberId: 2, role: 'MANAGER' });
    // Manager is never revoked, only passed on.
    expect(screen.queryByRole('button', { name: /Revoke Manager/ })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Team'), 'Operations');
    await user.click(await screen.findByRole('button', { name: 'Revoke Resource owner from Dave Kim' }));
    expect((await firstCall(server, 'DELETE /api/admin/roles/:memberId/:role')).params).toEqual({
      memberId: '7',
      role: 'RESOURCE_OWNER',
    });
  });

  it('shows the refusal of a role for a deactivated Member', async () => {
    serve({
      'POST /api/admin/roles': problem(422, 'MEMBER_INACTIVE', 'Ben Ortiz has been deactivated and holds no new role.'),
    });
    const { user } = renderApp('/admin/members');

    await user.click(await screen.findByRole('button', { name: 'Grant role to Ben Ortiz' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Ben Ortiz has been deactivated and holds no new role.');
  });

  it('is for Administrators only', async () => {
    mockApi({ 'GET /api/me': session(ben) });
    renderApp('/admin/members');
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
  });
});
