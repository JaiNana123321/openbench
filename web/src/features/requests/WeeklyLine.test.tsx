import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DraftView, RequestLine } from '../../api/types';
import { alice } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { roomA, types } from '../inventory/fixtures';
import { aliceDraft, gpuLine, roomLine } from './fixtures';

// Weekly recurrence of Exclusive lines in the Draft editor (SRS Draft.Line.Add): the field, the body
// sent, and how a weekly line shows. The server expands the occurrences and prices them.

/** Room A on D, D+7, and D+14, 10:00 to 12:00: Q5 = 90.00. */
const weeklyRoomLine: RequestLine = {
  ...roomLine,
  window: { start: '2026-12-02T10:00:00-05:00', end: '2026-12-02T12:00:00-05:00' },
  quote: 90,
  recurrence: { occurrences: 3 },
};

function serve(draft: DraftView = aliceDraft) {
  return mockApi({
    'GET /api/me': session(alice),
    'GET /api/requests/:id/draft': json(draft),
    'GET /api/inventory/types': json(types),
    'GET /api/inventory/resources': json({ content: [roomA], page: 0, size: 200, totalElements: 1 }),
    'POST /api/requests/:id/lines': json(weeklyRoomLine, 201),
  });
}

async function typeSelect() {
  await screen.findByRole('option', { name: 'GPU (Metered)' });
  return screen.getByLabelText('Resource Type');
}

describe('The Draft editor, weekly lines', () => {
  it('sends a weekly recurrence for an Exclusive line', async () => {
    const server = serve();
    const { user } = renderApp('/requests/42');

    await user.selectOptions(await typeSelect(), 'Meeting room (Exclusive)');
    fireEvent.change(screen.getByLabelText(/^Start/), { target: { value: '2026-12-02T10:00' } });
    fireEvent.change(screen.getByLabelText(/^End/), { target: { value: '2026-12-02T12:00' } });
    const weeks = screen.getByLabelText(/^Weekly occurrences/);
    await user.clear(weeks);
    await user.type(weeks, '3');
    await user.click(screen.getByRole('button', { name: 'Add line' }));

    await screen.findByRole('table', { name: 'Lines' });
    const [post] = server.callsTo('POST /api/requests/:id/lines');
    expect(JSON.parse(post.body ?? '')).toEqual({
      typeId: 2,
      quantity: 1,
      start: '2026-12-02T10:00:00-05:00',
      end: '2026-12-02T12:00:00-05:00',
      recurrence: { occurrences: 3 },
    });
  });

  it('sends no recurrence for one occurrence, and offers none for a Metered line', async () => {
    const server = serve();
    const { user } = renderApp('/requests/42');

    await user.selectOptions(await typeSelect(), 'GPU (Metered)');
    expect(screen.queryByLabelText(/^Weekly occurrences/)).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Resource Type'), 'Meeting room (Exclusive)');
    expect(screen.getByLabelText(/^Weekly occurrences/)).toHaveValue(1);
    fireEvent.change(screen.getByLabelText(/^Start/), { target: { value: '2026-12-02T10:00' } });
    fireEvent.change(screen.getByLabelText(/^End/), { target: { value: '2026-12-02T12:00' } });
    await user.click(screen.getByRole('button', { name: 'Add line' }));

    await screen.findByRole('table', { name: 'Lines' });
    const [post] = server.callsTo('POST /api/requests/:id/lines');
    expect(JSON.parse(post.body ?? '')).not.toHaveProperty('recurrence');
  });

  it('shows a weekly line’s first window and how many weeks it repeats, with the Quote for all of them', async () => {
    serve({ ...aliceDraft, lines: [weeklyRoomLine, gpuLine], requiredSum: 1210 });
    renderApp('/requests/42');

    const lines = await screen.findByRole('table', { name: 'Lines' });
    const [first] = within(lines).getAllByRole('row').slice(1);
    expect(within(first).getByText('Wed Dec 2, 10:00 to 12:00, weekly ×3')).toBeInTheDocument();
    expect(within(first).getByText('90.00')).toBeInTheDocument();
  });
});
