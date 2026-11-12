import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AdminChange, Page } from '../../api/types';
import { carol, dave } from '../../test/members';
import { json, mockApi, session, type MockRequest } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const granted: AdminChange = {
  id: 8,
  kind: 'ROLE_GRANTED',
  member: 2,
  memberName: 'Ben Ortiz',
  team: 9,
  teamName: 'Product Design',
  oldValue: 'MEMBER',
  newValue: 'MEMBER, MANAGER',
  actor: 6,
  actorName: 'Carol Davis',
  at: '2026-11-12T09:30:00-05:00',
};
const moved: AdminChange = {
  ...granted,
  id: 7,
  kind: 'MEMBER_MOVED',
  oldValue: 'Platform',
  newValue: 'Product Design',
  at: '2026-11-12T09:29:00-05:00',
};
const created: AdminChange = {
  ...granted,
  id: 6,
  kind: 'TEAM_CREATED',
  member: null,
  memberName: null,
  oldValue: null,
  newValue: 'Design',
  at: '2026-11-12T09:28:00-05:00',
};

describe('The change record', () => {
  it('shows each change with its actor, old and new value, and timestamp (adm-teams-roles, step 2)', async () => {
    const server = mockApi({
      'GET /api/me': session(carol),
      'GET /api/admin/changes': (request: MockRequest) => {
        const pageNumber = Number(request.query.get('page'));
        const body: Page<AdminChange> = {
          content: pageNumber === 0 ? [granted, moved] : [created],
          page: pageNumber,
          size: 50,
          totalElements: 51,
        };
        return json(body);
      },
    });
    const { user } = renderApp('/admin/changes');

    const table = await screen.findByRole('table', { name: 'Changes' });
    const row = within(table).getByRole('row', { name: /Role granted/ });
    expect(row).toHaveTextContent('Ben Ortiz');
    expect(row).toHaveTextContent('Product Design');
    expect(row).toHaveTextContent('MEMBER, MANAGER');
    expect(row).toHaveTextContent('Carol Davis');
    expect(row).toHaveTextContent('Thu Nov 12, 09:30');
    expect(within(table).getByRole('row', { name: /Member moved/ })).toHaveTextContent('Platform');

    await user.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByRole('row', { name: /Team created/ })).toHaveTextContent('None');
    expect(server.callsTo('GET /api/admin/changes').at(-1)?.query.get('page')).toBe('1');
  });

  it('is for Administrators only', async () => {
    mockApi({ 'GET /api/me': session(dave) });
    renderApp('/admin/changes');
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
  });
});
