import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LedgerTransaction, Me, Period, Statement } from '../../api/types';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const november: Period = { id: 11, startsOn: '2026-11-01', endsOn: '2026-11-30', previous: null };

const oscar: Me = {
  id: 8,
  name: 'Oscar Reed',
  username: 'oscar',
  team: { id: 3, name: 'Operations' },
  roles: ['MANAGER', 'MEMBER'],
};

const grant: LedgerTransaction = {
  id: 1,
  kind: 'GRANT',
  amount: 1000,
  grantedPart: 1000,
  earnedPart: 0,
  postedAt: '2026-11-01T00:00:00-05:00',
  line: null,
  actor: null,
  actorName: null,
  reason: null,
  loan: null,
};

/** mat-loan-form: Operations earns 800.00 for Dave Kim's Loan to Research, on Research's line 103. */
const earning: LedgerTransaction = {
  id: 12,
  kind: 'EARNING',
  amount: 800,
  grantedPart: 0,
  earnedPart: 800,
  postedAt: '2026-11-13T09:00:00-05:00',
  line: 103,
  actor: oscar.id,
  actorName: 'Oscar Reed',
  reason: 'Dave Kim to Research, Wed 25 Nov 2026 to Thu 26 Nov 2026, 2 person-days',
  loan: 77,
};

const statement: Statement = {
  team: oscar.team,
  period: november,
  opening: { granted: 0, earned: 0 },
  totals: { grants: 1000, charges: 0, earnings: 800, refunds: 0, expired: 0 },
  closing: { granted: 1000, earned: 800 },
  account: { team: 3, granted: 1000, earned: 800, held: 0, balance: 1800, available: 1800 },
  transactions: [grant, earning],
};

describe('StatementPage, Earnings', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-13T14:00:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows an Earning with its Loan, in earned credits', async () => {
    mockApi({
      'GET /api/me': session(oscar),
      'GET /api/settings/periods': json([november]),
      'GET /api/ledger/statements/:team/:period': json(statement),
      // The borrowing Team's line is not the lending Manager's to open.
      'GET /api/requests/lines': json([]),
    });
    renderApp('/statement');

    const table = await screen.findByRole('table', { name: 'Transactions' });
    const row = within(table).getByRole('row', { name: /Earning/ });
    expect(row).toHaveTextContent('800.00');
    expect(row).toHaveTextContent('Loan #77, Line 103');
    expect(row).toHaveTextContent('Dave Kim to Research, Wed 25 Nov 2026 to Thu 26 Nov 2026, 2 person-days');
    expect(screen.getByText('Earnings').nextElementSibling).toHaveTextContent('800.00');
    expect(screen.getByText('Earned').nextElementSibling).toHaveTextContent('800.00');
  });
});
