import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { PlannerSettings, ResourceType } from '../../api/types';
import { erin } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

// The service tiers of a Metered type on the Settings screen (SRS Metered.Tier.Define; test case
// met-tier-define, step 1): listed, added, their premiums edited, and removed, saved together.

const period = { id: 11, startsOn: '2000-02-01', endsOn: '2998-12-31', previous: null };
const gpu: ResourceType = { id: 6, name: 'GPU', allocationClass: 'METERED', attributes: [], retired: false, poolCapacity: 16 };

const settings: PlannerSettings = {
  currentPeriod: period,
  objectionWindowWorkingDays: 2,
  dayRate: 400,
  priorityPremium: 2,
  pricing: {
    6: {
      unitPrice: 5,
      bands: [],
      refundSchedule: { steps: [] },
      bumpCutoff: 'PT24H',
      tiers: [{ id: 7, name: 'Standard', premium: 1 }],
    },
  },
  quotas: {},
  grants: {},
  periodMonths: 1,
};

function serve(routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(erin),
    'GET /api/settings': json(settings),
    'GET /api/settings/periods': json([period]),
    'GET /api/settings/grants/:period': json({}),
    'GET /api/settings/changes': json({ content: [], page: 0, size: 20, totalElements: 0 }),
    'GET /api/teams': json([]),
    'GET /api/inventory/types': json([gpu]),
    'PUT /api/settings/:key': json(settings),
    ...routes,
  });
}

describe('Capacity Planner settings, service tiers', () => {
  it("lists a Metered type's tiers, adds Express, and saves every tier under tiers.{typeId}", async () => {
    const server = serve();
    const { user } = renderApp('/settings');

    const form = within(await screen.findByRole('form', { name: 'Service tiers' }));
    expect(form.getByRole('group', { name: 'Tier 1' })).toBeInTheDocument();
    expect(within(form.getByRole('group', { name: 'Tier 1' })).getByLabelText('Name')).toHaveValue('Standard');

    await user.click(form.getByRole('button', { name: 'Add tier' }));
    const express = within(form.getByRole('group', { name: 'Tier 2' }));
    await user.type(express.getByLabelText('Name'), 'Express');
    await user.clear(express.getByLabelText('Premium'));
    await user.type(express.getByLabelText('Premium'), '1.5');
    await user.click(form.getByRole('button', { name: 'Save tiers' }));

    await form.findByRole('status');
    const [put] = server.callsTo('PUT /api/settings/:key');
    expect(put.params.key).toBe('tiers.6');
    expect(JSON.parse(put.body ?? '')).toEqual({
      value: [
        { id: 7, name: 'Standard', premium: 1 },
        { id: null, name: 'Express', premium: 1.5 },
      ],
    });
  });

  it('shows the refusal to remove a tier a line refers to', async () => {
    serve({
      'PUT /api/settings/:key': problem(422, 'TIER_IN_USE', 'A line refers to the tier Standard; it cannot be removed.'),
    });
    const { user } = renderApp('/settings');

    const form = within(await screen.findByRole('form', { name: 'Service tiers' }));
    await user.click(form.getByRole('button', { name: 'Remove tier 1' }));
    await user.click(form.getByRole('button', { name: 'Save tiers' }));

    expect(await form.findByText('A line refers to the tier Standard; it cannot be removed.')).toBeInTheDocument();
  });
});
