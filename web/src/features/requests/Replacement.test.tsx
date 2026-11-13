import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { LineDetail, RequestDetail, RequestLine, Shortlist } from '../../api/types';
import { alice } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { aliceDraft, roomLine } from './fixtures';

// mat-refuse-replace and mat-refuse-none on the Request detail: Oscar refused Dave Kim for Alice's
// A1, so M1 needs a replacement, A1 is Waiting, and the Objection Window still ends on Monday; and a
// Request Refused with its lines canceled. Ids are made up.

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
  state: 'REFUSED',
  quote: null,
  recurrence: null,
  priority: false,
  namedMember: null,
  namedMemberName: null,
  skills: [{ id: 31, name: 'Statistics', retired: false }],
  days: { from: '2026-11-25', to: '2026-11-26' },
};

const refusedDave: NonNullable<LineDetail['candidates']>[number] = {
  approval: 9,
  member: 7,
  name: 'Dave Kim',
  team: { id: 3, name: 'Operations' },
  state: 'REFUSED',
  escalated: false,
  decidedByName: 'Oscar Reed',
  decidedAt: '2026-11-13T09:00:00-05:00',
  reason: 'Dave is on another project',
};

function detail(state: RequestDetail['request']['state'], lines: LineDetail[]): RequestDetail {
  return {
    request: {
      id: 42,
      team: { id: 1, name: 'Platform' },
      state,
      createdAt: '2026-11-12T09:00:00-05:00',
      submittedAt: '2026-11-12T10:00:00-05:00',
      windowEndsAt: '2026-11-16T10:00:00-05:00',
    },
    lines,
    history: [],
  };
}

function lineDetail(line: RequestLine, reason: string | null, reasonMessage: string | null, candidates = [refusedDave]) {
  return {
    line,
    assignedResources: [],
    reason,
    reasonMessage,
    waitlistPosition: null,
    allocatedParts: [],
    candidates: line.allocationClass === 'MATCHED' ? candidates : [],
  };
}

const shortlist: Shortlist = {
  lineId: 103,
  candidates: [
    { position: 1, member: 7, name: 'Dave Kim', team: { id: 3, name: 'Operations' }, skills: ['Statistics'], personDaysLent: 0 },
    { position: 2, member: 4, name: 'Rita Gomez', team: { id: 2, name: 'Research' }, skills: ['Statistics'], personDaysLent: 0 },
  ],
};

function serve(shown: RequestDetail) {
  return mockApi({
    'GET /api/me': session(alice),
    'GET /api/requests/:id/draft': json({ ...aliceDraft, request: shown.request }),
    'GET /api/requests/:id': json(shown),
    'GET /api/requests/:id/lines/:lineId/shortlist': json(shortlist),
    'POST /api/requests/:id/lines/:lineId/candidates': json(shown),
  });
}

describe('A refused approval on the Request detail', () => {
  it('shows the line needing a replacement, its reason, and the unchanged window, and offers the Shortlist', async () => {
    const server = serve(
      detail('WAITING', [
        lineDetail(m1, 'REPLACEMENT_NEEDED', 'Dave Kim refused by Oscar Reed: Dave is on another project.'),
        lineDetail({ ...roomLine, state: 'EVALUATED' }, null, null),
      ]),
    );
    const { user } = renderApp('/requests/42');

    expect(await screen.findByText(/Objection Window until/)).toHaveTextContent('Objection Window until Mon Nov 16, 10:00');
    const lines = await screen.findByRole('table', { name: 'Lines' });
    const row = within(lines).getAllByRole('row')[1];
    expect(within(row).getByText('Refused')).toBeInTheDocument();
    expect(within(row).getByText('Replacement needed')).toBeInTheDocument();
    expect(within(row).getByText(/Dave is on another project/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Replacement needed: a Candidate was refused.');

    expect(await screen.findByRole('checkbox', { name: 'Select Dave Kim' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Select Rita Gomez' }));
    await user.click(screen.getByRole('button', { name: 'Select 1 Candidate' }));
    await screen.findByRole('table', { name: 'Lines' });
    expect(JSON.parse(server.callsTo('POST /api/requests/:id/lines/:lineId/candidates')[0].body ?? '')).toEqual({
      members: [4],
    });
  });

  it('shows a Refused Request with the refused line’s reason, and nothing to select or withdraw', async () => {
    serve(
      detail('REFUSED', [
        lineDetail({ ...m1, state: 'CANCELED' }, 'APPROVAL_REFUSED', 'Dave Kim refused by Oscar Reed. The line named that Person.'),
        lineDetail({ ...roomLine, state: 'CANCELED' }, null, null),
      ]),
    );
    renderApp('/requests/42');

    const lines = await screen.findByRole('table', { name: 'Lines' });
    expect(screen.getAllByText('Refused').length).toBeGreaterThan(0);
    const row = within(lines).getAllByRole('row')[1];
    expect(within(row).getByText('Approval refused')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Shortlist of line 1' })).not.toBeInTheDocument();
  });
});
