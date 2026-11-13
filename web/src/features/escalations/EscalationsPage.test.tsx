import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { EscalatedRequest } from '../../api/types';
import { alice, erin } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

// mat-escalate: Alice's A1 lapsed on Monday at 10:00 with Oscar's approval of Dave Kim outstanding;
// Erin, the Capacity Planner, decides it on Oscar's behalf. Ids are made up.

const a1: EscalatedRequest = {
  id: 42,
  team: { id: 1, name: 'Platform' },
  state: 'WAITING',
  lapsedAt: '2026-11-16T10:00:00-05:00',
  approvals: [
    {
      id: 9,
      lineId: 103,
      required: true,
      candidate: 7,
      candidateName: 'Dave Kim',
      lendingTeam: { id: 3, name: 'Operations' },
      silentManager: 5,
      silentManagerName: 'Oscar Reed',
      days: { from: '2026-11-25', to: '2026-11-26' },
      earning: 800,
    },
  ],
};

function serve(routes: Record<string, MockReply> = {}, me = erin) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/escalations': json([a1]),
    ...routes,
  });
}

describe('Escalations', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-16T13:05:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('lists each escalated Request with its Candidate, the silent Manager, and the time since the lapse', async () => {
    serve();
    renderApp('/escalations');

    const panel = await screen.findByRole('region', { name: 'Request #42, Platform' });
    expect(within(panel).getByRole('link', { name: 'Request #42' })).toHaveAttribute('href', '/requests/42');
    expect(within(panel).getByText('Waiting')).toBeInTheDocument();
    expect(within(panel).getByText(/3 h ago/)).toBeInTheDocument();
    const row = within(within(panel).getByRole('table', { name: 'Escalated approvals of Request 42' })).getAllByRole(
      'row',
    )[1];
    expect(within(row).getByText('Dave Kim')).toBeInTheDocument();
    expect(within(row).getByText('Operations')).toBeInTheDocument();
    expect(within(row).getByText('Oscar Reed')).toBeInTheDocument();
    expect(within(row).getByText('Nov 25, 2026 to Nov 26, 2026')).toBeInTheDocument();
    expect(within(row).getByText('Required')).toBeInTheDocument();
    expect(within(row).getByText('800.00')).toBeInTheDocument();
  });

  it('approves for the silent Manager with a reason, as the API takes it, and refreshes the list', async () => {
    const server = serve({ 'POST /api/approvals/:id/decision': json({}) });
    const { user } = renderApp('/escalations');

    await user.type(await screen.findByRole('textbox', { name: 'Reason for Dave Kim' }), 'Oscar is away');
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    await vi.waitFor(() => expect(server.callsTo('GET /api/escalations')).toHaveLength(2));
    const [post] = server.callsTo('POST /api/approvals/:id/decision');
    expect(post.path).toBe('/api/approvals/9/decision');
    expect(JSON.parse(post.body ?? '')).toEqual({ decision: 'APPROVE', reason: 'Oscar is away' });
  });

  it('refuses, and shows a decision the server rejects as it words it', async () => {
    const server = serve({
      'POST /api/approvals/:id/decision': problem(409, 'ALREADY_DECIDED', 'The approval of Dave Kim is already approved.'),
    });
    const { user } = renderApp('/escalations');

    await user.click(await screen.findByRole('button', { name: 'Refuse' }));

    expect(await screen.findByText('The approval of Dave Kim is already approved.')).toBeInTheDocument();
    expect(JSON.parse(server.callsTo('POST /api/approvals/:id/decision')[0].body ?? '')).toEqual({
      decision: 'REFUSE',
      reason: '',
    });
  });

  it('says so when nothing is escalated', async () => {
    serve({ 'GET /api/escalations': json([]) });
    renderApp('/escalations');
    expect(await screen.findByText('No approval is escalated.')).toBeInTheDocument();
  });

  it('is in the menu of Capacity Planners only', async () => {
    serve();
    renderApp('/');

    const nav = await screen.findByRole('navigation');
    expect(within(nav).getByRole('link', { name: 'Escalations' })).toBeInTheDocument();
  });

  it('is not offered to a Manager', async () => {
    serve({}, alice);
    renderApp('/');

    const nav = await screen.findByRole('navigation');
    expect(within(nav).queryByRole('link', { name: 'Escalations' })).not.toBeInTheDocument();
  });
});
