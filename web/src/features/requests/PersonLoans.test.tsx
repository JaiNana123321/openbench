import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { RequestDetail, RequestLine } from '../../api/types';
import { alice } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { aliceDraft, roomLine } from './fixtures';

// Alice's A1 of lif-allocate once Oscar approved Dave Kim: Allocated, M1 Loaned, Room A Allocated.

const m1: RequestLine = {
  id: 103,
  requestId: 42,
  typeId: 1,
  typeName: 'Person',
  allocationClass: 'MATCHED',
  quantity: 1,
  window: { start: '2026-11-25T00:00:00-05:00', end: '2026-11-27T00:00:00-05:00' },
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
  days: { from: '2026-11-25', to: '2026-11-26' },
};

const allocated: RequestDetail = {
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
      candidates: [
        {
          approval: 501,
          member: 7,
          name: 'Dave Kim',
          team: { id: 3, name: 'Operations' },
          state: 'APPROVED',
          escalated: false,
          decidedByName: 'Oscar Reed',
          decidedAt: '2026-11-13T09:00:00-05:00',
          reason: null,
        },
      ],
      loans: [
        {
          id: 77,
          line: 103,
          member: 7,
          memberName: 'Dave Kim',
          lendingTeam: { id: 3, name: 'Operations' },
          borrowingTeam: { id: 1, name: 'Platform' },
          days: { from: '2026-11-25', to: '2026-11-26' },
          daysLent: 2,
          endedEarly: false,
        },
      ],
    },
    {
      line: { ...roomLine, state: 'ALLOCATED' },
      assignedResources: [{ id: 21, name: 'Room A' }],
      reason: null,
      reasonMessage: null,
      waitlistPosition: null,
      allocatedParts: [],
      candidates: [],
      loans: [],
    },
  ],
  history: [],
};

describe('A Person line’s Loans in the Request detail (lif-allocate)', () => {
  it('shows each Loan once formed: the Person, the lending Team, the days, and the days lent', async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: allocated.request }),
      'GET /api/requests/:id': json(allocated),
    });
    renderApp('/requests/42');

    const loans = await screen.findByRole('table', { name: 'Loans of line 1' });
    const row = within(loans).getByRole('row', { name: /Dave Kim/ });
    expect(row).toHaveTextContent('Operations');
    expect(row).toHaveTextContent('Nov 25, 2026 to Nov 26, 2026');
    expect(row).toHaveTextContent('2');
    expect(screen.queryByRole('table', { name: /Shortlist/ })).not.toBeInTheDocument();
  });

  it('shows no Loans before the line is allocated', async () => {
    const pending: RequestDetail = {
      ...allocated,
      request: { ...allocated.request, state: 'PENDING' },
      lines: [{ ...allocated.lines[0], line: { ...m1, state: 'APPROVED' }, loans: [] }, allocated.lines[1]],
    };
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: pending.request }),
      'GET /api/requests/:id': json(pending),
    });
    renderApp('/requests/42');

    expect(await screen.findByRole('table', { name: 'Candidates of line 1' })).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Loans of line 1' })).not.toBeInTheDocument();
  });
});
