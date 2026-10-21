import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Period, Statement } from '../../api/types';
import { alice, ben, erin } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { gpuLine, roomLine } from '../requests/fixtures';

const october: Period = { id: 10, startsOn: '2026-10-01', endsOn: '2026-10-31', previous: null };
const november: Period = { id: 11, startsOn: '2026-11-01', endsOn: '2026-11-30', previous: 10 };

/** Platform's October after Alice's Request is Allocated (the demo story). */
const platformOctober: Statement = {
  team: { id: 1, name: 'Platform' },
  period: october,
  opening: { granted: 0, earned: 0 },
  totals: { grants: 5000, charges: 1150, earnings: 0, refunds: 0, expired: 0 },
  closing: { granted: 3850, earned: 0 },
  account: { team: 1, granted: 3850, earned: 0, held: 0, balance: 3850, available: 3850 },
  transactions: [
    {
      id: 1,
      kind: 'GRANT',
      amount: 5000,
      grantedPart: 5000,
      earnedPart: 0,
      postedAt: '2026-10-01T00:00:00-04:00',
      line: null,
      actor: null,
      actorName: null,
      reason: null,
    },
    {
      id: 7,
      kind: 'CHARGE',
      amount: 30,
      grantedPart: 30,
      earnedPart: 0,
      postedAt: '2026-10-23T10:15:00-04:00',
      line: 41,
      actor: 1,
      actorName: 'Alice Chen',
      reason: null,
    },
    {
      id: 8,
      kind: 'CHARGE',
      amount: 1120,
      grantedPart: 1120,
      earnedPart: 0,
      postedAt: '2026-10-23T10:15:00-04:00',
      line: 42,
      actor: 1,
      actorName: 'Alice Chen',
      reason: null,
    },
  ],
};

const researchOctober: Statement = {
  ...platformOctober,
  team: { id: 2, name: 'Research' },
  totals: { grants: 600, charges: 0, earnings: 0, refunds: 0, expired: 0 },
  closing: { granted: 600, earned: 0 },
  account: { team: 2, granted: 600, earned: 0, held: 0, balance: 600, available: 600 },
  transactions: [
    { ...platformOctober.transactions[0], id: 2, amount: 600, grantedPart: 600, reason: 'Kick-off' },
  ],
};

const operationsOctober: Statement = { ...platformOctober, team: { id: 3, name: 'Operations' } };

describe('StatementPage', () => {
  beforeEach(() => {
    // The demo day, so the Period containing today is October.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-23T14:00:00-04:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a Manager their Team’s Account and the Transactions of the current Period', async () => {
    const server = mockApi({
      'GET /api/me': session(alice),
      'GET /api/settings/periods': json([october, november]),
      'GET /api/ledger/statements/:team/:period': json(platformOctober),
    });
    renderApp('/statement');

    const account = await screen.findByRole('region', { name: 'Account now' });
    expect(within(account).getByText('Balance').nextElementSibling).toHaveTextContent('3,850.00');
    expect(within(account).getByText('Available Balance').nextElementSibling).toHaveTextContent('3,850.00');
    const period = screen.getByRole('region', { name: 'This Period' });
    expect(within(period).getByText('Grants').nextElementSibling).toHaveTextContent('5,000.00');
    expect(within(period).getByText('Charges').nextElementSibling).toHaveTextContent('1,150.00');
    expect(within(period).getByText('Closing granted').nextElementSibling).toHaveTextContent('3,850.00');

    const rows = within(screen.getByRole('table', { name: 'Transactions' })).getAllByRole('row');
    expect(rows).toHaveLength(4);
    expect(rows[1]).toHaveTextContent('Thu Oct 1, 00:00');
    expect(rows[1]).toHaveTextContent('Grant');
    expect(rows[1]).toHaveTextContent('5,000.00');
    expect(rows[1]).toHaveTextContent('System');
    expect(rows[3]).toHaveTextContent('Charge');
    expect(rows[3]).toHaveTextContent('1,120.00');
    expect(rows[3]).toHaveTextContent('Line 42');
    expect(rows[3]).toHaveTextContent('Alice Chen');

    expect(screen.getByText('Platform Team, Oct 1, 2026 to Oct 31, 2026')).toBeInTheDocument();
    expect(screen.queryByLabelText('Team')).not.toBeInTheDocument();
    expect(server.callsTo('GET /api/ledger/statements/:team/:period').map((call) => call.path)).toEqual([
      '/api/ledger/statements/1/10',
    ]);
  });

  it('links each Charge to its Request', async () => {
    const server = mockApi({
      'GET /api/me': session(alice),
      'GET /api/settings/periods': json([october, november]),
      'GET /api/ledger/statements/:team/:period': json(platformOctober),
      'GET /api/requests/lines': json([
        { ...roomLine, id: 41 },
        { ...gpuLine, id: 42 },
      ]),
    });
    renderApp('/statement');

    expect(await screen.findAllByRole('link', { name: 'Request #42' })).toHaveLength(2);
    const rows = within(screen.getByRole('table', { name: 'Transactions' })).getAllByRole('row');
    expect(within(rows[2]).getByRole('link', { name: 'Request #42' })).toHaveAttribute('href', '/requests/42');
    expect(rows[2]).toHaveTextContent('Meeting room');
    expect(within(rows[3]).getByRole('link', { name: 'Request #42' })).toHaveAttribute('href', '/requests/42');
    expect(rows[3]).toHaveTextContent('GPU');
    expect(server.callsTo('GET /api/requests/lines').map((call) => call.query.get('ids'))).toEqual(['41,42']);
  });

  it('lets a Manager pick another Period', async () => {
    const platformNovember: Statement = { ...platformOctober, period: november, transactions: [] };
    const server = mockApi({
      'GET /api/me': session(alice),
      'GET /api/settings/periods': json([october, november]),
      'GET /api/ledger/statements/:team/:period': (request) =>
        json(request.params.period === '11' ? platformNovember : platformOctober),
    });
    const { user } = renderApp('/statement');

    await user.selectOptions(await screen.findByLabelText('Period'), 'Nov 1, 2026 to Nov 30, 2026');

    expect(await screen.findByText('No Transactions in this Period.')).toBeInTheDocument();
    expect(server.calls.map((call) => call.path)).toContain('/api/ledger/statements/1/11');
  });

  it('lets the Capacity Planner pick any Team', async () => {
    const server = mockApi({
      'GET /api/me': session(erin),
      'GET /api/settings/periods': json([october, november]),
      'GET /api/teams': json([
        { id: 3, name: 'Operations' },
        { id: 1, name: 'Platform' },
        { id: 2, name: 'Research' },
      ]),
      'GET /api/ledger/statements/:team/:period': (request) =>
        json(request.params.team === '2' ? researchOctober : operationsOctober),
    });
    const { user } = renderApp('/statement');

    await user.selectOptions(await screen.findByLabelText('Team'), 'Research');

    expect(await screen.findByText('Research Team, Oct 1, 2026 to Oct 31, 2026')).toBeInTheDocument();
    const rows = within(screen.getByRole('table', { name: 'Transactions' })).getAllByRole('row');
    expect(rows).toHaveLength(2);
    expect(rows[1]).toHaveTextContent('600.00');
    expect(rows[1]).toHaveTextContent('Kick-off');
    expect(server.callsTo('GET /api/ledger/statements/:team/:period').map((call) => call.path)).toEqual([
      '/api/ledger/statements/3/10',
      '/api/ledger/statements/2/10',
    ]);
  });

  it('shows Not found when the server hides the Statement', async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/settings/periods': json([october]),
      'GET /api/ledger/statements/:team/:period': problem(404, 'NOT_FOUND', 'Team 1 does not exist.'),
    });
    renderApp('/statement');

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });

  it('is in the menu for Managers and the Capacity Planner only', async () => {
    mockApi({ 'GET /api/me': session(ben) });
    renderApp('/statement');

    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Statement' })).not.toBeInTheDocument();
  });
});
