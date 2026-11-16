import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MeteredUsage, Period } from '../../api/types';
import { alice, ben, dave } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const september: Period = { id: 9, startsOn: '2026-09-01', endsOn: '2026-09-30', previous: null };
const october: Period = { id: 10, startsOn: '2026-10-01', endsOn: '2026-10-31', previous: 9 };
const november: Period = { id: 11, startsOn: '2026-11-01', endsOn: '2026-11-30', previous: 10 };

/** met-usage-record: Alice's 4 GPU units for E 10:00 to 12:00 in the ended October. */
const alicesGpu: MeteredUsage = {
  id: 70,
  team: { id: 1, name: 'Platform' },
  request: 42,
  type: 3,
  typeName: 'GPU',
  units: 4,
  window: { start: '2026-10-21T10:00:00-04:00', end: '2026-10-21T12:00:00-04:00' },
  requestedUnitHours: 8,
  usedUnits: null,
  recordedAt: null,
  recordedBy: null,
  recordable: true,
};

const usageApi = {
  'GET /api/settings/periods': json([september, october, november]),
  'GET /api/usage': json([alicesGpu]),
};

function gpuRow(): HTMLElement {
  const table = screen.getByRole('table', { name: 'Metered allocations' });
  const row = within(table).getAllByRole('row').find((candidate) => within(candidate).queryByText('GPU') !== null);
  if (!row) throw new Error('No GPU row.');
  return row;
}

describe('UsagePage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-17T08:00:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('lists the latest ended Period’s allocations and records the units used (met-usage-record)', async () => {
    const recorded: MeteredUsage = {
      ...alicesGpu,
      usedUnits: 3,
      recordedAt: '2026-11-17T08:00:00-05:00',
      recordedBy: 'Dave Kim',
    };
    let current = alicesGpu;
    const server = mockApi({
      'GET /api/me': session(dave),
      ...usageApi,
      'GET /api/usage': () => json([current]),
      'PUT /api/usage/:id': () => {
        current = recorded;
        return json(recorded);
      },
    });
    const { user } = renderApp('/usage');

    await screen.findByRole('table', { name: 'Metered allocations' });
    expect(gpuRow()).toHaveTextContent('Platform');
    expect(gpuRow()).toHaveTextContent('#42');
    expect(gpuRow()).toHaveTextContent('8.00');
    expect(gpuRow()).toHaveTextContent('Not yet');
    expect(server.callsTo('GET /api/usage').map((call) => call.query.get('periodId'))).toEqual(['10']);

    await user.type(within(gpuRow()).getByLabelText('Units used'), '3');
    await user.click(within(gpuRow()).getByRole('button', { name: 'Record' }));

    await vi.waitFor(() => expect(gpuRow()).toHaveTextContent('Dave Kim'));
    const put = server.callsTo('PUT /api/usage/:id');
    expect(put.map((call) => call.path)).toEqual(['/api/usage/70']);
    expect(JSON.parse(put[0].body ?? '')).toEqual({ usedUnits: 3 });
  });

  it('offers only ended Periods, newest first, and reads the one chosen', async () => {
    const server = mockApi({ 'GET /api/me': session(dave), ...usageApi });
    const { user } = renderApp('/usage');

    const picker = await screen.findByLabelText('Period');
    expect(within(picker).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Oct 1, 2026 to Oct 31, 2026',
      'Sep 1, 2026 to Sep 30, 2026',
    ]);
    await user.selectOptions(picker, 'Sep 1, 2026 to Sep 30, 2026');

    await vi.waitFor(() =>
      expect(server.callsTo('GET /api/usage').map((call) => call.query.get('periodId'))).toEqual(['10', '9']),
    );
  });

  it('shows the server’s refusal next to the allocation', async () => {
    mockApi({
      'GET /api/me': session(dave),
      ...usageApi,
      'PUT /api/usage/:id': problem(400, 'VALIDATION_FAILED', 'Units used must be from 0 to the 4 units allocated.'),
    });
    const { user } = renderApp('/usage');

    await screen.findByRole('table', { name: 'Metered allocations' });
    await user.type(within(gpuRow()).getByLabelText('Units used'), '4');
    await user.click(within(gpuRow()).getByRole('button', { name: 'Record' }));

    expect(await within(gpuRow()).findByText('Units used must be from 0 to the 4 units allocated.')).toBeInTheDocument();
  });

  it('offers no form for an allocation whose Period has not ended', async () => {
    mockApi({
      'GET /api/me': session(dave),
      ...usageApi,
      'GET /api/usage': json([{ ...alicesGpu, recordable: false }]),
    });
    renderApp('/usage');

    await screen.findByRole('table', { name: 'Metered allocations' });
    expect(gpuRow()).toHaveTextContent('After its Period ends');
    expect(within(gpuRow()).queryByRole('button', { name: 'Record' })).not.toBeInTheDocument();
  });

  it('says so when the Resource Owner has no allocations in the Period', async () => {
    mockApi({ 'GET /api/me': session(dave), ...usageApi, 'GET /api/usage': json([]) });
    renderApp('/usage');

    expect(await screen.findByText('No Metered allocations of your types in this Period.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Metered usage' })).toBeInTheDocument();
  });

  it('is offered only to Resource Owners', async () => {
    for (const member of [alice, ben]) {
      mockApi({ 'GET /api/me': session(member) });
      const { unmount } = renderApp('/usage');

      expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: 'Metered usage' })).not.toBeInTheDocument();
      unmount();
    }
  });
});
