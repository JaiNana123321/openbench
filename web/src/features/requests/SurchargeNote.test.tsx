import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DraftView } from '../../api/types';
import { alice } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { roomA, types } from '../inventory/fixtures';
import { aliceAllocated, aliceDraft, gpuLine } from './fixtures';

// The demand surcharge in the Draft editor (SRS Ledger.Surcharge; test case led-surcharge, step 3): the
// Quote the server priced, with a short note of the multiplier in it.

function serve(draft: DraftView) {
  return mockApi({
    'GET /api/suggestions/request/:id': json([]),
    'GET /api/me': session(alice),
    'GET /api/requests/:id/draft': json(draft),
    'GET /api/requests/:id': json(aliceAllocated),
    'GET /api/inventory/types': json(types),
    'GET /api/inventory/resources': json({ content: [roomA], page: 0, size: 200, totalElements: 1 }),
    'GET /api/settings/tiers/:typeId': json([]),
  });
}

describe('The Draft editor, the demand surcharge', () => {
  it('shows the surcharged Quote with its multiplier, and no note without a surcharge', async () => {
    serve({ ...aliceDraft, lines: [aliceDraft.lines[0], { ...gpuLine, quote: 90, surcharge: 1.5 }] });
    renderApp('/requests/42');

    const rows = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    expect(rows[1]).toHaveTextContent('90.00×1.5 demand');
    expect(rows[0]).not.toHaveTextContent('demand');
  });
});
