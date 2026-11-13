import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApprovalView } from '../../api/types';
import { bob, dave } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

// Oscar's approval of mat-approve-route: Dave Kim for Platform, Wed Nov 25 and Thu Nov 26, Earning
// 800.00, with the Objection Window ending on Monday at 10:00. Oscar is played by Bob's fixture
// (a Manager); the ids are made up.

const daveForPlatform: ApprovalView = {
  id: 9,
  requestId: 42,
  lineId: 103,
  candidate: 7,
  candidateName: 'Dave Kim',
  requestingTeam: { id: 1, name: 'Platform' },
  days: { from: '2026-11-25', to: '2026-11-26' },
  earning: 800,
  windowEndsAt: '2026-11-16T10:00:00-05:00',
  state: 'OUTSTANDING',
  escalated: false,
  decidedByName: null,
  decidedAt: null,
  reason: null,
};

function serve(routes: Record<string, MockReply> = {}, me = bob) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/approvals': json([daveForPlatform]),
    ...routes,
  });
}

describe('Approvals', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-14T07:00:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('lists the approval with the requesting Team, the days, the Earning, and the time left', async () => {
    serve();
    renderApp('/approvals');

    const table = await screen.findByRole('table', { name: 'Approvals' });
    const row = within(table).getAllByRole('row')[1];
    expect(within(row).getByText('Dave Kim')).toBeInTheDocument();
    expect(within(row).getByText('Platform')).toBeInTheDocument();
    expect(within(row).getByText('Nov 25, 2026 to Nov 26, 2026')).toBeInTheDocument();
    expect(within(row).getByText('800.00')).toBeInTheDocument();
    expect(within(row).getByText('2 days 3 h left')).toBeInTheDocument();
  });

  it('approves with a reason, as the API takes it, and refreshes the list', async () => {
    const server = serve({ 'POST /api/approvals/:id/decision': json({ ...daveForPlatform, state: 'APPROVED' }) });
    const { user } = renderApp('/approvals');

    await user.type(await screen.findByRole('textbox', { name: 'Reason for Dave Kim' }), 'Dave is free that week');
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    await vi.waitFor(() => expect(server.callsTo('GET /api/approvals')).toHaveLength(2));
    const [post] = server.callsTo('POST /api/approvals/:id/decision');
    expect(post.path).toBe('/api/approvals/9/decision');
    expect(JSON.parse(post.body ?? '')).toEqual({ decision: 'APPROVE', reason: 'Dave is free that week' });
  });

  it('shows a refused decision as the server words it', async () => {
    serve({
      'POST /api/approvals/:id/decision': problem(409, 'WINDOW_LAPSED', 'The Objection Window of Request 42 has ended.'),
    });
    const { user } = renderApp('/approvals');

    await user.click(await screen.findByRole('button', { name: 'Refuse' }));

    expect(await screen.findByText('The Objection Window of Request 42 has ended.')).toBeInTheDocument();
  });

  it('says so when nothing awaits a decision, and shows a lapsed window', async () => {
    serve({ 'GET /api/approvals': json([{ ...daveForPlatform, windowEndsAt: '2026-11-13T10:00:00-05:00' }]) });
    renderApp('/approvals');
    expect(await screen.findByText('Lapsed')).toBeInTheDocument();
  });

  it('is in the menu of Managers only', async () => {
    serve({ 'GET /api/approvals': json([]) }, dave);
    renderApp('/');

    const nav = await screen.findByRole('navigation');
    expect(within(nav).queryByRole('link', { name: 'Approvals' })).not.toBeInTheDocument();
  });

  it('shows an approval the Capacity Planner decided for the Manager, without a decision form (mat-escalate)', async () => {
    serve({
      'GET /api/approvals': json([
        {
          ...daveForPlatform,
          state: 'APPROVED',
          escalated: true,
          decidedByName: 'Erin Walsh',
          decidedAt: '2026-11-16T11:00:00-05:00',
          reason: 'Oscar is away',
        },
      ]),
    });
    renderApp('/approvals');

    const table = await screen.findByRole('table', { name: 'Approvals' });
    const row = within(table).getAllByRole('row')[1];
    expect(within(row).getByText('Approved')).toBeInTheDocument();
    expect(within(row).getByText(/by Erin Walsh/)).toHaveTextContent('by Erin Walsh, Capacity Planner');
    expect(within(row).getByText('Oscar is away')).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('shows an empty list', async () => {
    serve({ 'GET /api/approvals': json([]) });
    renderApp('/approvals');
    expect(await screen.findByText('No approval awaits your decision.')).toBeInTheDocument();
  });
});
