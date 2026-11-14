import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { RequestDetail } from '../../api/types';
import { bob } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { types } from '../inventory/fixtures';
import { aliceDraft, bobWaiting, gpuLine } from './fixtures';

// A line Bumped by a Priority line (SRS Exclusive.Bump.Effect; test case exc-bump-effect): its Request is
// Waiting, the line waits first on its waitlist, the other line stays allocated, and the history lists the Bump.

const bumpNote = 'Bumped by Priority line 201 of Request 50; full Refund of 30.00 credits.';

const bumped: RequestDetail = {
  ...bobWaiting,
  lines: [
    {
      ...bobWaiting.lines[0],
      reason: 'BUMPED',
      reasonMessage: 'Bumped by a Priority line (Priority line 201 of Request 50); position 1 on the waitlist.',
      waitlistPosition: 1,
    },
    {
      ...bobWaiting.lines[1],
      line: { ...gpuLine, id: 112, requestId: 43, state: 'ALLOCATED', quote: 60 },
      reason: null,
      reasonMessage: null,
      waitlistPosition: null,
    },
  ],
  history: [
    ...bobWaiting.history.slice(0, 2),
    {
      at: '2026-10-24T09:00:00-04:00',
      line: 111,
      fromState: 'ALLOCATED',
      toState: 'BUMPED',
      actor: 1,
      actorName: 'Alice Chen',
      note: bumpNote,
    },
    {
      at: '2026-10-24T09:00:00-04:00',
      line: 111,
      fromState: 'BUMPED',
      toState: 'WAITING',
      actor: 1,
      actorName: 'Alice Chen',
      note: 'BUMPED: Bumped by a Priority line (Priority line 201 of Request 50); position 1 on the waitlist.',
    },
  ],
};

describe('The Request detail, a Bumped line', () => {
  it('shows the line Bumped by a Priority line with its waitlist position, and the Bump in the history', async () => {
    mockApi({
      'GET /api/me': session(bob),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: bumped.request }),
      'GET /api/requests/:id': json(bumped),
      'GET /api/inventory/types': json(types),
    });
    renderApp('/requests/43');

    const rows = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('Waiting');
    expect(rows[0]).toHaveTextContent('Bumped by a Priority line');
    expect(rows[0]).toHaveTextContent('Position 1');
    expect(rows[1]).toHaveTextContent('Allocated');
    expect(rows[1]).not.toHaveTextContent('Bumped by a Priority line');

    const history = within(screen.getByRole('list', { name: 'History' })).getAllByRole('listitem');
    expect(history[2]).toHaveTextContent('Alice Chen: Line 1 Allocated to Bumped');
    expect(history[2]).toHaveTextContent(bumpNote);
    expect(history[3]).toHaveTextContent('Line 1 Bumped to Waiting');
  });

  it('shows a line waiting for another reason without the Bump text', async () => {
    mockApi({
      'GET /api/me': session(bob),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: bobWaiting.request }),
      'GET /api/requests/:id': json(bobWaiting),
      'GET /api/inventory/types': json(types),
    });
    renderApp('/requests/43');

    const rows = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('Waiting');
    expect(rows[0]).not.toHaveTextContent('Bumped by a Priority line');
  });
});
