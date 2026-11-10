import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DraftView } from '../../api/types';
import { alice } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { roomA, types } from '../inventory/fixtures';
import { aliceAllocated, aliceDraft, gpuLine, roomLine } from './fixtures';

// The Priority mark in the Draft editor (SRS Exclusive.Priority.Bump, the drafting part): a checkbox on
// Exclusive lines, sent to PUT .../priority; the server prices the premium and refuses other classes.

function serve(draft: DraftView = aliceDraft, routes = {}) {
  return mockApi({
    'GET /api/me': session(alice),
    'GET /api/requests/:id/draft': json(draft),
    'GET /api/requests/:id': json(aliceAllocated),
    'GET /api/inventory/types': json(types),
    'GET /api/inventory/resources': json({ content: [roomA], page: 0, size: 200, totalElements: 1 }),
    'PUT /api/requests/:id/lines/:lineId/priority': json({ ...roomLine, priority: true, quote: 60 }),
    ...routes,
  });
}

describe('The Draft editor, the Priority mark', () => {
  it('offers the mark on Exclusive lines only and sends it to the priority endpoint', async () => {
    const server = serve();
    const { user } = renderApp('/requests/42');

    await user.click(await screen.findByRole('checkbox', { name: 'Line 1 Priority' }));
    expect(screen.queryByRole('checkbox', { name: 'Line 2 Priority' })).not.toBeInTheDocument();

    const [mark] = server.callsTo('PUT /api/requests/:id/lines/:lineId/priority');
    expect(mark.path).toBe('/api/requests/42/lines/101/priority');
    expect(JSON.parse(mark.body ?? '')).toEqual({ priority: true });
  });

  it('shows a Priority line checked, with the Quote the server priced', async () => {
    serve({ ...aliceDraft, lines: [{ ...roomLine, priority: true, quote: 60 }, gpuLine], requiredSum: 1180 });
    renderApp('/requests/42');

    const checkbox = await screen.findByRole('checkbox', { name: 'Line 1 Priority' });
    expect(checkbox).toBeChecked();
    const [first] = within(screen.getByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    expect(within(first).getByText('60.00')).toBeInTheDocument();
  });

  it('shows a refusal of the mark as the server words it', async () => {
    serve(aliceDraft, {
      'PUT /api/requests/:id/lines/:lineId/priority': problem(
        422,
        'PRIORITY_NOT_OFFERED',
        'A GPU line cannot be marked Priority; Priority is offered for Exclusive lines only.',
      ),
    });
    const { user } = renderApp('/requests/42');

    await user.click(await screen.findByRole('checkbox', { name: 'Line 1 Priority' }));
    expect(
      await screen.findByText('A GPU line cannot be marked Priority; Priority is offered for Exclusive lines only.'),
    ).toBeInTheDocument();
  });
});
