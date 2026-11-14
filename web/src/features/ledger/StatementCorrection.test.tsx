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

/** rel-loan-prorate: Operations earned 1200.00 for Dave Kim's Loan to Research, D to D+2. */
const earning: LedgerTransaction = {
  id: 12,
  kind: 'EARNING',
  amount: 1200,
  grantedPart: 0,
  earnedPart: 1200,
  postedAt: '2026-11-13T09:00:00-05:00',
  line: 103,
  actor: oscar.id,
  actorName: 'Oscar Reed',
  reason: 'Dave Kim to Research, Wed 25 Nov 2026 to Fri 27 Nov 2026, 3 person-days',
  loan: 77,
  corrects: null,
};

/** Bob ended the Loan early with last day D: a correction of -800.00 naming the original Earning. */
const correction: LedgerTransaction = {
  ...earning,
  id: 15,
  amount: -800,
  earnedPart: -800,
  postedAt: '2026-11-16T11:00:00-05:00',
  actor: 3,
  actorName: 'Bob Patel',
  reason: 'Correction: Dave Kim to Research ended early on Wed 25 Nov 2026, 2 person-days no longer lent',
  corrects: 12,
};

const statement: Statement = {
  team: oscar.team,
  period: november,
  opening: { granted: 0, earned: 0 },
  totals: { grants: 0, charges: 0, earnings: 400, refunds: 0, expired: 0 },
  closing: { granted: 0, earned: 400 },
  account: { team: 3, granted: 0, earned: 400, held: 0, balance: 400, available: 400 },
  transactions: [earning, correction],
};

describe('StatementPage, an Earning correction (rel-loan-prorate)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-16T14:00:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the correction as a negative Earning that refers to the original, and the net in the totals', async () => {
    mockApi({
      'GET /api/me': session(oscar),
      'GET /api/settings/periods': json([november]),
      'GET /api/ledger/statements/:team/:period': json(statement),
      'GET /api/requests/lines': json([]),
    });
    renderApp('/statement');

    const table = await screen.findByRole('table', { name: 'Transactions' });
    const rows = within(table).getAllByRole('row', { name: /Earning/ });
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('1,200.00');
    expect(rows[0]).not.toHaveTextContent('corrects');
    expect(rows[1]).toHaveTextContent('-800.00');
    expect(rows[1]).toHaveTextContent('Loan #77, Line 103');
    expect(rows[1]).toHaveTextContent('Bob Patel');
    expect(rows[1]).toHaveTextContent('2 person-days no longer lent (corrects the Earning of');
    expect(screen.getByText('Earnings').nextElementSibling).toHaveTextContent('400.00');
  });
});
