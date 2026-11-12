import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { TeamOverview } from '../../api/types';
import { carol, erin } from '../../test/members';
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
const design: TeamOverview = {
  id: 9,
  name: 'Design',
  retired: false,
  manager: null,
  managerName: null,
  activeMembers: 0,
};
const archive: TeamOverview = {
  id: 4,
  name: 'Archive',
  retired: true,
  manager: null,
  managerName: null,
  activeMembers: 0,
};

function serve(routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(carol),
    'GET /api/admin/teams': json([archive, design, platform]),
    ...routes,
  });
}

async function firstCall(server: ReturnType<typeof mockApi>, route: string): Promise<MockRequest> {
  await waitFor(() => expect(server.callsTo(route)).not.toHaveLength(0));
  return server.callsTo(route)[0];
}

describe('Teams administration', () => {
  it('lists every Team with its Manager, active Members, and status', async () => {
    serve();
    renderApp('/admin/teams');

    const table = await screen.findByRole('table', { name: 'Teams' });
    const platformRow = within(table).getByRole('row', { name: /Platform/ });
    expect(platformRow).toHaveTextContent('Alice Chen');
    expect(platformRow).toHaveTextContent('Active');
    expect(within(table).getByRole('row', { name: /Design/ })).toHaveTextContent('None');
    expect(within(table).getByRole('row', { name: /Archive/ })).toHaveTextContent('Retired');
    expect(screen.queryByRole('button', { name: 'Retire Archive' })).not.toBeInTheDocument();
  });

  it('adds the Team Design, then renames it Product Design (adm-teams-roles, step 1)', async () => {
    const server = serve({
      'POST /api/admin/teams': json(design, 201),
      'PUT /api/admin/teams/:id': json({ ...design, name: 'Product Design' }),
    });
    const { user } = renderApp('/admin/teams');

    await user.type(await screen.findByLabelText('Name'), 'Design');
    await user.click(screen.getByRole('button', { name: 'Add Team' }));
    expect(JSON.parse((await firstCall(server, 'POST /api/admin/teams')).body ?? '')).toEqual({ name: 'Design' });

    await user.click(screen.getByRole('button', { name: 'Rename Design' }));
    const input = screen.getByLabelText('New name for Design');
    await user.clear(input);
    await user.type(input, 'Product Design');
    await user.click(screen.getByRole('button', { name: 'Save name' }));

    const call = await firstCall(server, 'PUT /api/admin/teams/:id');
    expect(call.params.id).toBe('9');
    expect(JSON.parse(call.body ?? '')).toEqual({ name: 'Product Design' });
    await waitFor(() => expect(screen.queryByLabelText('New name for Design')).not.toBeInTheDocument());
  });

  it('shows the refusal of a taken name', async () => {
    serve({ 'POST /api/admin/teams': problem(422, 'TEAM_NAME_TAKEN', 'A Team named platform already exists.') });
    const { user } = renderApp('/admin/teams');

    await user.type(await screen.findByLabelText('Name'), 'platform');
    await user.click(screen.getByRole('button', { name: 'Add Team' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('A Team named platform already exists.');
  });

  it('retires a Team, and shows the refusal while it has active Members (adm-teams-roles, step 5)', async () => {
    const server = serve({
      'POST /api/admin/teams/:id/retire': (request: MockRequest) =>
        request.params.id === '1'
          ? problem(422, 'TEAM_NOT_EMPTY', 'Platform still has 2 active Members; move or deactivate them first.')
          : json({ ...design, retired: true }),
    });
    const { user } = renderApp('/admin/teams');

    await user.click(await screen.findByRole('button', { name: 'Retire Design' }));
    expect((await firstCall(server, 'POST /api/admin/teams/:id/retire')).params.id).toBe('9');

    await user.click(screen.getByRole('button', { name: 'Retire Platform' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Platform still has 2 active Members; move or deactivate them first.',
    );
  });

  it('is for Administrators only', async () => {
    mockApi({ 'GET /api/me': session(erin) });
    renderApp('/admin/teams');
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
  });
});
