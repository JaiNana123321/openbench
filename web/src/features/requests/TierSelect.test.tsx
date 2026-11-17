import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DraftView, RequestDetail, ServiceTier } from '../../api/types';
import { alice, bob } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { roomA, types } from '../inventory/fixtures';
import { aliceAllocated, aliceDraft, bobWaiting, gpuLine } from './fixtures';

// The service tier in the Draft editor (SRS Metered.Tier.Define; test case met-tier-define, step 2): a
// select on Metered lines, None then each tier, saved with the line; the server prices the premium. The
// Request detail shows the tier, and a Metered line Bumped by a higher tier (met-tier-bump).

const tiers: ServiceTier[] = [
  { id: 7, name: 'Standard', premium: 1 },
  { id: 8, name: 'Express', premium: 1.5 },
];

function serve(draft: DraftView = aliceDraft, routes = {}) {
  return mockApi({
    'GET /api/suggestions/request/:id': json([]),
    'GET /api/me': session(alice),
    'GET /api/requests/:id/draft': json(draft),
    'GET /api/requests/:id': json(aliceAllocated),
    'GET /api/inventory/types': json(types),
    'GET /api/inventory/resources': json({ content: [roomA], page: 0, size: 200, totalElements: 1 }),
    'GET /api/settings/tiers/:typeId': json(tiers),
    'PUT /api/requests/:id/lines/:lineId': json({ ...gpuLine, tier: 8, tierName: 'Express', quote: 1680 }),
    ...routes,
  });
}

describe('The Draft editor, the service tier', () => {
  it('offers None and each tier on Metered lines only, and saves the choice with the line', async () => {
    const server = serve();
    const { user } = renderApp('/requests/42');

    const select = await screen.findByRole('combobox', { name: 'Line 2 tier' });
    expect(within(select).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'None',
      'Standard ×1',
      'Express ×1.5',
    ]);
    expect(screen.queryByRole('combobox', { name: 'Line 1 tier' })).not.toBeInTheDocument();
    expect(server.callsTo('GET /api/settings/tiers/:typeId')[0].path).toBe('/api/settings/tiers/4');

    await user.selectOptions(select, 'Express ×1.5');
    const [edit] = server.callsTo('PUT /api/requests/:id/lines/:lineId');
    expect(edit.path).toBe('/api/requests/42/lines/102');
    expect(JSON.parse(edit.body ?? '')).toEqual({
      quantity: 4,
      start: gpuLine.window.start,
      end: gpuLine.window.end,
      tierId: 8,
    });
  });

  it('shows the chosen tier with the Quote the server priced', async () => {
    serve({ ...aliceDraft, lines: [aliceDraft.lines[0], { ...gpuLine, tier: 8, tierName: 'Express', quote: 1680 }] });
    renderApp('/requests/42');

    const select = await screen.findByRole('combobox', { name: 'Line 2 tier' });
    await within(select).findByRole('option', { name: 'Express ×1.5' });
    expect(select).toHaveValue('8');
    const second = within(screen.getByRole('table', { name: 'Lines' })).getAllByRole('row')[2];
    expect(within(second).getByText('1,680.00')).toBeInTheDocument();
  });
});

describe('The Request detail, a tiered Metered line', () => {
  it('shows the tier, and a Metered line Bumped by a higher-tier line', async () => {
    const bumped: RequestDetail = {
      ...bobWaiting,
      lines: [
        {
          ...bobWaiting.lines[1],
          line: { ...gpuLine, id: 112, requestId: 43, state: 'WAITING', tier: 7, tierName: 'Standard' },
          reason: 'BUMPED',
          reasonMessage: 'Bumped by a higher-tier line (Higher-tier line 201 of Request 50); position 1 on the waitlist.',
          waitlistPosition: 1,
        },
      ],
    };
    mockApi({
      'GET /api/me': session(bob),
      'GET /api/requests/:id/draft': json({ ...aliceDraft, request: bumped.request }),
      'GET /api/requests/:id': json(bumped),
      'GET /api/inventory/types': json(types),
    });
    renderApp('/requests/43');

    const row = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row')[1];
    expect(row).toHaveTextContent('4 units · Standard');
    expect(row).toHaveTextContent('Bumped by a higher-tier line');
    expect(row).toHaveTextContent('Position 1');
  });
});
