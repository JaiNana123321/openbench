import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LedgerTransaction, Period, Statement } from '../../api/types';
import { alice } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { roomLine } from '../requests/fixtures';

const november: Period = { id: 11, startsOn: '2026-11-01', endsOn: '2026-11-30', previous: null };

function transaction(id: number, kind: LedgerTransaction['kind'], amount: number, line: number | null): LedgerTransaction {
  return {
    id,
    kind,
    amount,
    grantedPart: amount,
    earnedPart: 0,
    postedAt: '2026-11-09T10:00:00-05:00',
    line,
    actor: 1,
    actorName: 'Alice Chen',
    reason: null,
  };
}

/** rel-whole: Platform charged 30.00 for Room A and refunded 22.50 when the line was released. */
const afterRelease: Statement = {
  team: { id: 1, name: 'Platform' },
  period: november,
  opening: { granted: 0, earned: 0 },
  totals: { grants: 5000, charges: 30, earnings: 0, refunds: 22.5, expired: 0 },
  closing: { granted: 4992.5, earned: 0 },
  account: { team: 1, granted: 4992.5, earned: 0, held: 0, balance: 4992.5, available: 4992.5 },
  transactions: [transaction(1, 'GRANT', 5000, null), transaction(7, 'CHARGE', 30, 101), transaction(9, 'REFUND', 22.5, 101)],
};

describe('StatementPage, Refunds', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-09T14:00:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a Refund against the line it refunds, next to that line’s Charge', async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/settings/periods': json([november]),
      'GET /api/ledger/statements/:team/:period': json(afterRelease),
      'GET /api/requests/lines': json([{ ...roomLine, state: 'RELEASED' }]),
    });
    renderApp('/statement');

    expect(await screen.findAllByRole('link', { name: 'Request #42' })).toHaveLength(2);
    const rows = within(screen.getByRole('table', { name: 'Transactions' })).getAllByRole('row');
    expect(rows[3]).toHaveTextContent('Refund');
    expect(rows[3]).toHaveTextContent('22.50');
    expect(within(rows[3]).getByRole('link', { name: 'Request #42' })).toHaveAttribute('href', '/requests/42');
    expect(rows[3]).toHaveTextContent('Meeting room');
    expect(screen.getByText('Refunds').nextElementSibling).toHaveTextContent('22.50');
    expect(screen.getByText('Balance').nextElementSibling).toHaveTextContent('4,992.50');
  });
});
