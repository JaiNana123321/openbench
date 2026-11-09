import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me, Period, Statement } from '../../api/types';
import { bob } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

// led-period-boundary on screen: Operations had granted 970.00 and earned 800.00 when October
// ended, and Erin's Grant for November is 1000.00.
const october: Period = { id: 10, startsOn: '2026-10-01', endsOn: '2026-10-31', previous: null };
const november: Period = { id: 11, startsOn: '2026-11-01', endsOn: '2026-11-30', previous: 10 };

const oscar: Me = { ...bob, id: 5, name: 'Oscar Reed', username: 'oscar', team: { id: 3, name: 'Operations' } };

const account = { team: 3, granted: 1000, earned: 800, held: 0, balance: 1800, available: 1800 };

const operationsOctober: Statement = {
  team: { id: 3, name: 'Operations' },
  period: october,
  opening: { granted: 0, earned: 800 },
  totals: { grants: 1000, charges: 30, earnings: 0, refunds: 0, expired: 970 },
  closing: { granted: 0, earned: 800 },
  account,
  transactions: [],
};

const operationsNovember: Statement = {
  team: { id: 3, name: 'Operations' },
  period: november,
  opening: { granted: 0, earned: 800 },
  totals: { grants: 1000, charges: 0, earnings: 0, refunds: 0, expired: 0 },
  closing: { granted: 1000, earned: 800 },
  account,
  transactions: [
    {
      id: 30,
      kind: 'GRANT',
      amount: 1000,
      grantedPart: 1000,
      earnedPart: 0,
      postedAt: '2026-11-01T00:05:00-04:00',
      line: null,
      actor: null,
      actorName: null,
      reason: null,
    },
  ],
};

describe('StatementPage across a Period boundary', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-09T10:00:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the ended Period’s expired credits and closing balances, and the Grant the job posted', async () => {
    mockApi({
      'GET /api/me': session(oscar),
      'GET /api/notifications': json({ content: [], page: 0, size: 50, totalElements: 0 }),
      'GET /api/settings/periods': json([october, november]),
      'GET /api/ledger/statements/:team/:period': (request) =>
        json(request.params.period === '10' ? operationsOctober : operationsNovember),
    });
    const { user } = renderApp('/statement');

    // The new Period: opening granted 0.00 and earned 800.00, a Grant of 1000.00 by the system.
    let period = await screen.findByRole('region', { name: 'This Period' });
    expect(within(period).getByText('Opening granted').nextElementSibling).toHaveTextContent('0.00');
    expect(within(period).getByText('Opening earned').nextElementSibling).toHaveTextContent('800.00');
    const rows = within(screen.getByRole('table', { name: 'Transactions' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Grant');
    expect(rows[1]).toHaveTextContent('1,000.00');
    expect(rows[1]).toHaveTextContent('System');
    const now = screen.getByRole('region', { name: 'Account now' });
    expect(within(now).getByText('Balance').nextElementSibling).toHaveTextContent('1,800.00');

    // The ended Period: expired 970.00; closing granted 0.00, earned 800.00.
    await user.selectOptions(screen.getByLabelText('Period'), 'Oct 1, 2026 to Oct 31, 2026');
    await screen.findByText('Operations Team, Oct 1, 2026 to Oct 31, 2026');
    period = screen.getByRole('region', { name: 'This Period' });
    expect(within(period).getByText('Expired').nextElementSibling).toHaveTextContent('970.00');
    expect(within(period).getByText('Closing granted').nextElementSibling).toHaveTextContent('0.00');
    expect(within(period).getByText('Closing earned').nextElementSibling).toHaveTextContent('800.00');
  });
});
