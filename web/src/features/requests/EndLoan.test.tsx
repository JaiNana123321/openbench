import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Loan, RequestDetail, RequestLine } from '../../api/types';
import { alice, erin } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { aliceDraft } from './fixtures';

// rel-loan-prorate on Alice's A1: Dave Kim lent by Operations to Platform from Wed Nov 25 to Fri Nov 27,
// 2026 (D to D+2); Alice ends the Loan early with last day D. Today is Mon Nov 16.

const m1: RequestLine = {
  id: 103,
  requestId: 42,
  typeId: 1,
  typeName: 'Person',
  allocationClass: 'MATCHED',
  quantity: 1,
  window: { start: '2026-11-25T00:00:00-05:00', end: '2026-11-28T00:00:00-05:00' },
  namedResource: null,
  namedResourceName: null,
  required: true,
  state: 'LOANED',
  quote: null,
  recurrence: null,
  priority: false,
  namedMember: null,
  namedMemberName: null,
  skills: [{ id: 31, name: 'Statistics', retired: false }],
  days: { from: '2026-11-25', to: '2026-11-27' },
};

const loan: Loan = {
  id: 77,
  line: 103,
  member: 7,
  memberName: 'Dave Kim',
  lendingTeam: { id: 3, name: 'Operations' },
  borrowingTeam: { id: 1, name: 'Platform' },
  days: { from: '2026-11-25', to: '2026-11-27' },
  daysLent: 3,
  endedEarly: false,
};

const shortened: Loan = { ...loan, days: { from: '2026-11-25', to: '2026-11-25' }, daysLent: 1, endedEarly: true };

function detailWith(loans: Loan[]): RequestDetail {
  return {
    request: {
      id: 42,
      team: { id: 1, name: 'Platform' },
      state: 'ALLOCATED',
      createdAt: '2026-11-12T09:00:00-05:00',
      submittedAt: '2026-11-12T10:00:00-05:00',
      windowEndsAt: '2026-11-16T10:00:00-05:00',
    },
    lines: [
      {
        line: m1,
        assignedResources: [],
        reason: null,
        reasonMessage: null,
        waitlistPosition: null,
        allocatedParts: [],
        candidates: [],
        loans,
      },
    ],
    history: [],
  };
}

describe('Ending a Loan early from the Request detail (rel-loan-prorate)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-16T10:00:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('lets the borrowing Manager pick a new last day, confirm, and see the shortened Loan', async () => {
    let ended = false;
    const server = mockApi({
      'GET /api/me': session(alice),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: detailWith([]).request }),
      'GET /api/requests/:id': () => json(detailWith([ended ? shortened : loan])),
      'POST /api/loans/:id/end': () => {
        ended = true;
        return json(shortened);
      },
    });
    const { user } = renderApp('/requests/42');

    const loans = await screen.findByRole('table', { name: 'Loans of line 1' });
    await user.click(within(loans).getByRole('button', { name: 'End Loan early…' }));
    const date = screen.getByLabelText('New last day');
    expect(date).toHaveAttribute('min', '2026-11-25');
    expect(date).toHaveAttribute('max', '2026-11-26');
    await user.type(date, '2026-11-25');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByRole('status')).toHaveTextContent('End the Loan on Nov 25, 2026?');
    expect(screen.getByRole('status')).toHaveTextContent('Your Team gets no Refund and no Charge.');
    expect(server.callsTo('POST /api/loans/:id/end')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'End Loan early' }));

    expect(await screen.findByText(/now covers Nov 25, 2026 to Nov 25, 2026, 1 person-day lent/)).toBeInTheDocument();
    const posted = server.callsTo('POST /api/loans/:id/end');
    expect(posted.map((call) => call.path)).toEqual(['/api/loans/77/end']);
    expect(JSON.parse(posted[0].body ?? '')).toEqual({ newEnd: '2026-11-25' });
    const row = within(await screen.findByRole('table', { name: 'Loans of line 1' })).getByRole('row', {
      name: /Dave Kim/,
    });
    await vi.waitFor(() => expect(row).toHaveTextContent('Nov 25, 2026 to Nov 25, 2026'));
    expect(row).toHaveTextContent('Ended early');
  });

  it('shows the refusal and keeps the form', async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: detailWith([]).request }),
      'GET /api/requests/:id': json(detailWith([loan])),
      'POST /api/loans/:id/end': problem(422, 'LOAN_END_INVALID', 'The new last day must be before the last day.'),
    });
    const { user } = renderApp('/requests/42');

    const loans = await screen.findByRole('table', { name: 'Loans of line 1' });
    await user.click(within(loans).getByRole('button', { name: 'End Loan early…' }));
    await user.type(screen.getByLabelText('New last day'), '2026-11-26');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'End Loan early' }));

    expect(await screen.findByText('The new last day must be before the last day.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'End the Loan of Dave Kim early' })).toBeInTheDocument();
  });

  it('offers no early end on a Loan whose last day has come', async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: detailWith([]).request }),
      'GET /api/requests/:id': json(detailWith([{ ...loan, days: { from: '2026-11-13', to: '2026-11-16' } }])),
    });
    renderApp('/requests/42');
    const loans = await screen.findByRole('table', { name: 'Loans of line 1' });
    expect(within(loans).queryByRole('button')).not.toBeInTheDocument();
  });

  it('shows the Loans to a Capacity Planner without the action', async () => {
    mockApi({
      'GET /api/me': session(erin),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: detailWith([]).request }),
      'GET /api/requests/:id': json(detailWith([loan])),
    });
    renderApp('/requests/42');
    const loans = await screen.findByRole('table', { name: 'Loans of line 1' });
    expect(within(loans).queryByRole('button')).not.toBeInTheDocument();
    expect(within(loans).queryByRole('columnheader', { name: 'Action' })).not.toBeInTheDocument();
  });
});
