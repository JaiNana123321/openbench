import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Page, Period, PlannerSettings, ResourceType, SettingChangeEntry, TeamRef } from '../../api/types';
import { alice, carol, erin } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

// The Capacity Planner's Settings screen (adm-planner-settings through the client, led-grant-mid's
// form). The Periods lie far apart so one always contains today and one always lies ahead.

const past: Period = { id: 10, startsOn: '2000-01-01', endsOn: '2000-01-31', previous: null };
const current: Period = { id: 11, startsOn: '2000-02-01', endsOn: '2998-12-31', previous: 10 };
const next: Period = { id: 12, startsOn: '2999-01-01', endsOn: '2999-01-31', previous: 11 };

const platform: TeamRef = { id: 1, name: 'Platform' };
const research: TeamRef = { id: 2, name: 'Research' };

function type(id: number, name: string, allocationClass: ResourceType['allocationClass']): ResourceType {
  return { id, name, allocationClass, attributes: [], retired: false, poolCapacity: allocationClass === 'METERED' ? 16 : null };
}

const room = type(4, 'Meeting room', 'EXCLUSIVE');
const gpu = type(6, 'GPU', 'METERED');
const statistician = type(7, 'Statistician', 'MATCHED');

const weekdays = { weekdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'], start: '09:00:00', end: '17:00:00', multiplier: 1.5 } as const;
const weekend = { weekdays: ['SATURDAY', 'SUNDAY'], start: '00:00:00', end: '00:00:00', multiplier: 0.5 } as const;

const settings: PlannerSettings = {
  currentPeriod: current,
  objectionWindowWorkingDays: 2,
  dayRate: 400,
  priorityPremium: 2,
  pricing: {
    4: {
      unitPrice: 10,
      bands: [{ ...weekdays, weekdays: [...weekdays.weekdays] }, { ...weekend, weekdays: [...weekend.weekdays] }],
      refundSchedule: { steps: [{ minLead: 'PT0S', fraction: 0.25 }, { minLead: 'PT168H', fraction: 0.75 }] },
      bumpCutoff: 'PT24H',
    },
    6: { unitPrice: 5, bands: [], refundSchedule: { steps: [] }, bumpCutoff: null },
  },
  quotas: { '2:6': 8 },
  grants: { 1: 5000, 2: 600 },
  periodMonths: 1,
};

const changes: Page<SettingChangeEntry> = {
  content: [
    { id: 31, key: 'unit_price.4', oldValue: '10.00', newValue: '12.00', actor: 8, actorName: 'Erin Walsh', at: '2026-10-12T09:00:00-04:00' },
    { id: 30, key: 'quota.1.6', oldValue: null, newValue: '10', actor: 8, actorName: 'Erin Walsh', at: '2026-10-12T08:55:00-04:00' },
    { id: 29, key: 'grant.2.12', oldValue: '600.00', newValue: '700.00', actor: 8, actorName: 'Erin Walsh', at: '2026-10-12T08:50:00-04:00' },
    { id: 28, key: 'day_rate', oldValue: '400.00', newValue: '450.00', actor: 8, actorName: 'Erin Walsh', at: '2026-10-12T08:45:00-04:00' },
  ],
  page: 0,
  size: 20,
  totalElements: 4,
};

function serve(routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(erin),
    'GET /api/settings': json(settings),
    'GET /api/settings/periods': json([past, current, next]),
    'GET /api/settings/grants/:period': json({ 1: 5000, 2: 600 }),
    'GET /api/settings/changes': json(changes),
    'GET /api/teams': json([platform, research]),
    'GET /api/inventory/types': json([room, gpu, statistician]),
    'PUT /api/settings/:key': json(settings),
    ...routes,
  });
}

function form(name: string) {
  return screen.getByRole('form', { name });
}

describe('Capacity Planner settings', () => {
  it('shows each setting and saves the Day Rate through its key without a reload', async () => {
    const server = serve();
    const { user } = renderApp('/settings');

    const dayRate = within(await screen.findByRole('form', { name: 'Day Rate' }));
    expect(dayRate.getByLabelText('Day Rate')).toHaveValue(400);
    expect(within(form('Priority premium')).getByLabelText('Priority premium')).toHaveValue(2);
    expect(within(form('Objection Window (working days)')).getByRole('spinbutton')).toHaveValue(2);
    expect(within(form('Period length (months)')).getByRole('spinbutton')).toHaveValue(1);
    expect(within(form('Unit Price')).getByRole('spinbutton')).toHaveValue(10);
    expect(within(form('Bump cutoff (hours)')).getByRole('spinbutton')).toHaveValue(24);

    await user.clear(dayRate.getByLabelText('Day Rate'));
    await user.type(dayRate.getByLabelText('Day Rate'), '450');
    await user.click(dayRate.getByRole('button', { name: 'Save' }));

    expect(await dayRate.findByRole('status')).toHaveTextContent('Saved.');
    const put = server.callsTo('PUT /api/settings/:key')[0];
    expect(put.params.key).toBe('day_rate');
    expect(JSON.parse(put.body ?? '')).toEqual({ value: 450 });
    expect(server.callsTo('GET /api/settings').length).toBeGreaterThan(1);
    expect(server.callsTo('GET /api/settings/changes').length).toBeGreaterThan(1);
  });

  it('shows a refused Day Rate by its message', async () => {
    serve({
      'PUT /api/settings/:key': problem(422, 'VALUE_OUT_OF_RANGE', 'The Day Rate must be at least 0.'),
    });
    const { user } = renderApp('/settings');

    const dayRate = within(await screen.findByRole('form', { name: 'Day Rate' }));
    await user.clear(dayRate.getByLabelText('Day Rate'));
    await user.type(dayRate.getByLabelText('Day Rate'), '-1');
    await user.click(dayRate.getByRole('button', { name: 'Save' }));

    expect(await dayRate.findByRole('alert')).toHaveTextContent('The Day Rate must be at least 0.');
  });

  it('saves a type\'s whole Price Band list and shows BANDS_OVERLAP by its message', async () => {
    const server = serve({
      'PUT /api/settings/:key': problem(422, 'BANDS_OVERLAP', 'The Price Bands share an hour; Price Bands of a type may not overlap.'),
    });
    const { user } = renderApp('/settings');

    const bands = within(await screen.findByRole('form', { name: 'Price Bands' }));
    await user.click(bands.getByRole('button', { name: 'Add band' }));
    const added = within(bands.getByRole('group', { name: 'Band 3' }));
    await user.click(added.getByLabelText('Mon'));
    await user.clear(added.getByLabelText('Start'));
    await user.type(added.getByLabelText('Start'), '12:00');
    await user.clear(added.getByLabelText('End'));
    await user.type(added.getByLabelText('End'), '18:00');
    await user.clear(added.getByLabelText('Multiplier'));
    await user.type(added.getByLabelText('Multiplier'), '2');
    await user.click(bands.getByRole('button', { name: 'Save Price Bands' }));

    expect(await bands.findByRole('alert')).toHaveTextContent('Price Bands of a type may not overlap.');
    const put = server.callsTo('PUT /api/settings/:key')[0];
    expect(put.params.key).toBe('price_bands.4');
    expect(JSON.parse(put.body ?? '').value).toEqual([
      { weekdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'], start: '09:00', end: '17:00', multiplier: 1.5 },
      { weekdays: ['SATURDAY', 'SUNDAY'], start: '00:00', end: '00:00', multiplier: 0.5 },
      { weekdays: ['MONDAY'], start: '12:00', end: '18:00', multiplier: 2 },
    ]);
  });

  it('removes a band and saves the rest', async () => {
    const server = serve();
    const { user } = renderApp('/settings');

    const bands = within(await screen.findByRole('form', { name: 'Price Bands' }));
    await user.click(bands.getByRole('button', { name: 'Remove band 1' }));
    await user.click(bands.getByRole('button', { name: 'Save Price Bands' }));

    await bands.findByRole('status');
    expect(JSON.parse(server.callsTo('PUT /api/settings/:key')[0].body ?? '').value).toEqual([
      { weekdays: ['SATURDAY', 'SUNDAY'], start: '00:00', end: '00:00', multiplier: 0.5 },
    ]);
  });

  it('saves the Refund Schedule in hours and fractions and shows REFUND_STEP_TOO_HIGH by its message', async () => {
    const server = serve({
      'PUT /api/settings/:key': problem(422, 'REFUND_STEP_TOO_HIGH', 'A Refund Schedule step returns 100%; no step may return 100% or more.'),
    });
    const { user } = renderApp('/settings');

    const schedule = within(await screen.findByRole('form', { name: 'Refund Schedule' }));
    expect(within(schedule.getByRole('group', { name: 'Step 1' })).getByLabelText('Lead time (hours)')).toHaveValue(168);
    expect(within(schedule.getByRole('group', { name: 'Step 1' })).getByLabelText('Refund (%)')).toHaveValue(75);
    await user.click(schedule.getByRole('button', { name: 'Add step' }));
    const added = within(schedule.getByRole('group', { name: 'Step 3' }));
    await user.clear(added.getByLabelText('Lead time (hours)'));
    await user.type(added.getByLabelText('Lead time (hours)'), '336');
    await user.clear(added.getByLabelText('Refund (%)'));
    await user.type(added.getByLabelText('Refund (%)'), '100');
    await user.click(schedule.getByRole('button', { name: 'Save Refund Schedule' }));

    expect(await schedule.findByRole('alert')).toHaveTextContent('no step may return 100% or more.');
    const put = server.callsTo('PUT /api/settings/:key')[0];
    expect(put.params.key).toBe('refund_steps.4');
    expect(JSON.parse(put.body ?? '').value).toEqual([
      { minLead: 'PT168H', fraction: 0.75 },
      { minLead: 'PT0H', fraction: 0.25 },
      { minLead: 'PT336H', fraction: 1 },
    ]);
  });

  it('removes a refund step, and shows a Metered type without a bump cutoff', async () => {
    const server = serve();
    const { user } = renderApp('/settings');

    const schedule = within(await screen.findByRole('form', { name: 'Refund Schedule' }));
    await user.click(schedule.getByRole('button', { name: 'Remove step 2' }));
    await user.click(schedule.getByRole('button', { name: 'Save Refund Schedule' }));
    await schedule.findByRole('status');
    expect(JSON.parse(server.callsTo('PUT /api/settings/:key')[0].body ?? '').value).toEqual([
      { minLead: 'PT168H', fraction: 0.75 },
    ]);

    await user.selectOptions(screen.getByLabelText('Resource Type'), 'GPU');
    expect(within(form('Unit Price')).getByRole('spinbutton')).toHaveValue(5);
    expect(screen.queryByRole('form', { name: 'Bump cutoff (hours)' })).not.toBeInTheDocument();
    expect(within(form('Price Bands')).queryByRole('group')).not.toBeInTheDocument();
  });

  it('saves a bump cutoff in hours', async () => {
    const server = serve();
    const { user } = renderApp('/settings');

    const cutoff = within(await screen.findByRole('form', { name: 'Bump cutoff (hours)' }));
    await user.clear(cutoff.getByRole('spinbutton'));
    await user.type(cutoff.getByRole('spinbutton'), '48');
    await user.click(cutoff.getByRole('button', { name: 'Save' }));

    await cutoff.findByRole('status');
    const put = server.callsTo('PUT /api/settings/:key')[0];
    expect(put.params.key).toBe('bump_cutoff.4');
    expect(JSON.parse(put.body ?? '')).toEqual({ value: 'PT48H' });
  });

  it('sets a Team\'s Quota, removes it when left empty, and plans a Grant for a coming Period', async () => {
    const server = serve();
    const { user } = renderApp('/settings');

    const quota = within(await screen.findByRole('form', { name: 'Quota for GPU (units)' }));
    expect(quota.getByRole('spinbutton')).toHaveValue(null);
    await user.type(quota.getByRole('spinbutton'), '10');
    await user.click(quota.getByRole('button', { name: 'Save' }));
    await quota.findByRole('status');
    expect(server.callsTo('PUT /api/settings/:key')[0].params.key).toBe('quota.1.6');
    expect(JSON.parse(server.callsTo('PUT /api/settings/:key')[0].body ?? '')).toEqual({ value: 10 });

    const teams = screen.getByRole('region', { name: 'Grants and Quotas by Team' });
    await user.selectOptions(within(teams).getByLabelText('Team'), 'Research');
    const researchQuota = within(form('Quota for GPU (units)'));
    expect(researchQuota.getByRole('spinbutton')).toHaveValue(8);
    await user.clear(researchQuota.getByRole('spinbutton'));
    await user.click(researchQuota.getByRole('button', { name: 'Save' }));
    await researchQuota.findByRole('status');
    expect(JSON.parse(server.callsTo('PUT /api/settings/:key')[1].body ?? '')).toEqual({ value: null });

    expect(within(teams).getByLabelText('From the Period')).toHaveDisplayValue('Jan 1, 2999 to Jan 31, 2999');
    expect(server.callsTo('GET /api/settings/grants/:period')[0].params.period).toBe('12');
    const grant = within(form('Planned Grant'));
    expect(grant.getByRole('spinbutton')).toHaveValue(600);
    await user.clear(grant.getByRole('spinbutton'));
    await user.type(grant.getByRole('spinbutton'), '700');
    await user.click(grant.getByRole('button', { name: 'Save' }));
    await grant.findByRole('status');
    expect(server.callsTo('PUT /api/settings/:key')[2].params.key).toBe('grant.2.12');
    expect(JSON.parse(server.callsTo('PUT /api/settings/:key')[2].body ?? '')).toEqual({ value: 700 });
  });

  it('lists the history of changes with the setting in words, the old and new value, and the actor', async () => {
    serve();
    renderApp('/settings');

    const table = await screen.findByRole('table', { name: 'Setting changes' });
    expect(within(table).getByRole('row', { name: /Unit Price, Meeting room/ })).toHaveTextContent(
      /10\.00.*12\.00.*Erin Walsh/,
    );
    expect(within(table).getByRole('row', { name: /Quota, Platform, GPU/ })).toHaveTextContent(/None.*10/);
    expect(within(table).getByRole('row', { name: /Planned Grant, Research from Jan 1, 2999/ })).toHaveTextContent(
      /600\.00.*700\.00/,
    );
    expect(within(table).getByRole('row', { name: /Day Rate/ })).toHaveTextContent(/400\.00.*450\.00/);
  });

  it('pages through a long history', async () => {
    const server = serve({ 'GET /api/settings/changes': json({ ...changes, totalElements: 45 }) });
    const { user } = renderApp('/settings');

    await screen.findByRole('table', { name: 'Setting changes' });
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();
    expect(server.callsTo('GET /api/settings/changes').at(-1)?.query.get('page')).toBe('1');
  });

  it('posts a mid-Period Grant with a reason and shows a refusal by its message (led-grant-mid)', async () => {
    const server = serve({
      'POST /api/ledger/grants': (request) =>
        JSON.parse(request.body ?? '').reason === ''
          ? problem(422, 'REASON_REQUIRED', 'Give a reason for a mid-Period Grant.')
          : json({ team: 2, granted: 800, earned: 0, held: 0, balance: 800, available: 800 }),
    });
    const { user } = renderApp('/settings');

    const grant = within(await screen.findByRole('form', { name: 'Mid-Period Grant' }));
    await user.selectOptions(grant.getByLabelText('Team'), 'Research');
    await user.type(grant.getByLabelText('Amount'), '200');
    await user.click(grant.getByRole('button', { name: 'Post Grant' }));
    expect(await grant.findByRole('alert')).toHaveTextContent('Give a reason for a mid-Period Grant.');

    await user.type(grant.getByLabelText('Reason'), 'Extra imaging time');
    await user.click(grant.getByRole('button', { name: 'Post Grant' }));

    expect(await grant.findByRole('status')).toHaveTextContent("Granted. The Team's Balance is now 800.00.");
    expect(JSON.parse(server.callsTo('POST /api/ledger/grants')[1].body ?? '')).toEqual({
      teamId: 2,
      amount: 200,
      reason: 'Extra imaging time',
    });
  });

  it('offers Settings only to the Capacity Planner', async () => {
    serve({ 'GET /api/me': session(alice) });
    renderApp('/settings');
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument();
  });

  it('refuses the Administrator the Settings screen (adm-skills-no-policy)', async () => {
    serve({ 'GET /api/me': session(carol) });
    renderApp('/settings');
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument();
  });

  it('shows the Settings link to the Capacity Planner', async () => {
    serve();
    renderApp('/');
    expect(await screen.findAllByRole('link', { name: /Settings/ })).not.toHaveLength(0);
  });
});
