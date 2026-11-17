import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { PlannerSettings, ResourceType } from '../../api/types';
import { erin } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

// The demand surcharge on the Settings screen (SRS Ledger.Surcharge; test case led-surcharge, step 1):
// turned on with a rate of 1.0 unless the Planner types another, or off with 0.

const period = { id: 11, startsOn: '2000-02-01', endsOn: '2998-12-31', previous: null };
const gpu: ResourceType = { id: 6, name: 'GPU', allocationClass: 'METERED', attributes: [], retired: false, poolCapacity: 16 };

function settings(surchargeRate: number): PlannerSettings {
  return {
    currentPeriod: period,
    objectionWindowWorkingDays: 2,
    dayRate: 400,
    priorityPremium: 2,
    pricing: { 6: { unitPrice: 5, bands: [], refundSchedule: { steps: [] }, bumpCutoff: null, tiers: [], surchargeRate } },
    quotas: {},
    grants: {},
    periodMonths: 1,
  };
}

function serve(rate: number, routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(erin),
    'GET /api/settings': json(settings(rate)),
    'GET /api/settings/periods': json([period]),
    'GET /api/settings/grants/:period': json({}),
    'GET /api/settings/changes': json({ content: [], page: 0, size: 20, totalElements: 0 }),
    'GET /api/teams': json([]),
    'GET /api/inventory/types': json([gpu]),
    'PUT /api/settings/:key': json(settings(1)),
    ...routes,
  });
}

describe('Capacity Planner settings, the demand surcharge', () => {
  it('turns the surcharge on at a rate of 1.0 under surcharge.{typeId}', async () => {
    const server = serve(0);
    const { user } = renderApp('/settings');

    const form = within(await screen.findByRole('form', { name: 'Demand surcharge' }));
    expect(form.getByRole('checkbox', { name: 'Surcharge on' })).not.toBeChecked();
    expect(form.queryByLabelText('Rate')).not.toBeInTheDocument();
    await user.click(form.getByRole('checkbox', { name: 'Surcharge on' }));
    expect(form.getByLabelText('Rate')).toHaveValue(1);
    await user.click(form.getByRole('button', { name: 'Save surcharge' }));

    await form.findByRole('status');
    const [put] = server.callsTo('PUT /api/settings/:key');
    expect(put.params.key).toBe('surcharge.6');
    expect(JSON.parse(put.body ?? '')).toEqual({ value: 1 });
  });

  it('turns it off with a rate of 0, and shows a refusal', async () => {
    const server = serve(0.5, {
      'PUT /api/settings/:key': problem(400, 'VALIDATION_FAILED', 'A demand surcharge rate is 0 or more.'),
    });
    const { user } = renderApp('/settings');

    const form = within(await screen.findByRole('form', { name: 'Demand surcharge' }));
    expect(form.getByLabelText('Rate')).toHaveValue(0.5);
    await user.click(form.getByRole('checkbox', { name: 'Surcharge on' }));
    await user.click(form.getByRole('button', { name: 'Save surcharge' }));

    expect(await form.findByText('A demand surcharge rate is 0 or more.')).toBeInTheDocument();
    expect(JSON.parse(server.callsTo('PUT /api/settings/:key')[0].body ?? '')).toEqual({ value: 0 });
  });
});
