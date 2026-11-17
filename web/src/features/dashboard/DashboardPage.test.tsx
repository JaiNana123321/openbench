import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  Period,
  PersonDays,
  Statement,
  TeamCredits,
  TeamTypeUsage,
  TypeIdle,
  TypeUtilization,
  TypeWaiting,
} from '../../api/types';
import { alice, ben, dave, erin } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const october: Period = { id: 10, startsOn: '2026-10-01', endsOn: '2026-10-31', previous: null };
const november: Period = { id: 11, startsOn: '2026-11-01', endsOn: '2026-11-30', previous: 10 };
const december: Period = { id: 12, startsOn: '2026-12-01', endsOn: '2026-12-31', previous: 11 };

/** dsh-utilization in a 30-day November at 08:00 on Nov 1: Room A 4 of 1440 instance-hours, the GPU 8 of 11520 unit-hours. */
const utilization: TypeUtilization[] = [
  {
    type: 2,
    name: 'Confocal microscope',
    allocationClass: 'EXCLUSIVE',
    period: 11,
    capacity: 1,
    availableHours: 720,
    allocatedHours: 0,
    idleHours: 720,
    toDate: '2026-11-01T12:00:00Z',
    idleHoursToDate: 8,
    utilizationPercent: 0,
  },
  {
    type: 3,
    name: 'GPU',
    allocationClass: 'METERED',
    period: 11,
    capacity: 16,
    availableHours: 11520,
    allocatedHours: 8,
    idleHours: 11512,
    toDate: '2026-11-01T12:00:00Z',
    idleHoursToDate: 128,
    utilizationPercent: 0.07,
  },
  {
    type: 1,
    name: 'Meeting room',
    allocationClass: 'EXCLUSIVE',
    period: 11,
    capacity: 2,
    availableHours: 1440,
    allocatedHours: 4,
    idleHours: 1436,
    toDate: '2026-11-01T12:00:00Z',
    idleHoursToDate: 16,
    utilizationPercent: 0.28,
  },
];

const idleWeek = (type: number, capacity: number, idleHours: number): TypeIdle => ({
  type,
  name: '',
  allocationClass: 'EXCLUSIVE',
  days: 7,
  from: '2026-11-17T08:00:00-05:00',
  to: '2026-11-24T08:00:00-05:00',
  capacity,
  availableHours: capacity * 168,
  allocatedHours: capacity * 168 - idleHours,
  idleHours,
});
const idle: TypeIdle[] = [idleWeek(1, 2, 332), idleWeek(2, 1, 168), idleWeek(3, 16, 2680)];

const waiting: TypeWaiting[] = [
  { type: 1, name: 'Meeting room', allocationClass: 'EXCLUSIVE', waiting: 1 },
  { type: 2, name: 'Confocal microscope', allocationClass: 'EXCLUSIVE', waiting: 0 },
  { type: 3, name: 'GPU', allocationClass: 'METERED', waiting: 0 },
];

/** dsh-credits. */
const teamCredits = (team: number, teamName: string, figures: Partial<TeamCredits>): TeamCredits => ({
  team,
  teamName,
  period: 11,
  grants: 0,
  charges: 0,
  earnings: 0,
  refunds: 0,
  expired: 0,
  balance: 0,
  available: 0,
  ended: false,
  ...figures,
});
const credits: TeamCredits[] = [
  teamCredits(3, 'Operations', { grants: 1000, earnings: 800, balance: 1800, available: 1800 }),
  teamCredits(1, 'Platform', { grants: 5000, charges: 30, refunds: 22.5, balance: 4992.5, available: 4992.5 }),
  teamCredits(2, 'Research', { grants: 600, balance: 600, available: 600 }),
];
const personDays: PersonDays[] = [
  { team: 3, teamName: 'Operations', period: 11, lent: 2, borrowed: 0 },
  { team: 1, teamName: 'Platform', period: 11, lent: 0, borrowed: 0 },
  { team: 2, teamName: 'Research', period: 11, lent: 0, borrowed: 2 },
];

const dashboardApi = {
  'GET /api/settings/periods': json([october, november, december]),
  'GET /api/dashboard/utilization': json(utilization),
  'GET /api/dashboard/idle': json(idle),
  'GET /api/dashboard/waiting': json(waiting),
  'GET /api/dashboard/credits': json(credits),
  'GET /api/dashboard/person-days': json(personDays),
  'GET /api/dashboard/usage': json([]),
};

/** dsh-usage: Platform requested 4 GPU units for E 10:00 to 12:00 and 3 were recorded as used. */
const gpuUsage: TeamTypeUsage = {
  team: 1,
  teamName: 'Platform',
  type: 3,
  typeName: 'GPU',
  period: 10,
  requestedUnitHours: 8,
  usedUnitHours: 6,
  recorded: 1,
  unrecorded: 0,
};

function rowOf(table: string, name: string): HTMLElement {
  const rows = within(screen.getByRole('table', { name: table })).getAllByRole('row');
  const row = rows.find((candidate) => within(candidate).queryByText(name) !== null);
  if (!row) throw new Error(`No row ${name} in ${table}.`);
  return row;
}

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-17T08:00:00-05:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the Capacity Planner utilization, idle to date and next week, and waiting lines per type, and every Team’s credits', async () => {
    const server = mockApi({ 'GET /api/me': session(erin), ...dashboardApi });
    renderApp('/dashboard');

    await screen.findByRole('table', { name: 'Utilization' });
    const room = rowOf('Utilization', 'Meeting room');
    expect(room).toHaveTextContent('0.28%');
    expect(room).toHaveTextContent('4.00');
    expect(room).toHaveTextContent('332.00');
    expect(within(room).getAllByRole('cell').at(-3)).toHaveTextContent('16.00');
    expect(within(room).getAllByRole('cell').at(-1)).toHaveTextContent('1');
    expect(rowOf('Utilization', 'GPU')).toHaveTextContent('0.07%');
    expect(rowOf('Utilization', 'GPU')).toHaveTextContent('2,680.00');
    expect(within(rowOf('Utilization', 'GPU')).getAllByRole('cell').at(-3)).toHaveTextContent('128.00');
    expect(rowOf('Utilization', 'Confocal microscope')).toHaveTextContent('0.00%');

    await screen.findByRole('table', { name: 'Team credits' });
    const platform = rowOf('Team credits', 'Platform');
    expect(platform).toHaveTextContent('5,000.00');
    expect(platform).toHaveTextContent('22.50');
    expect(platform).toHaveTextContent('4,992.50');
    expect(within(platform).getByRole('link', { name: 'Platform' })).toHaveAttribute(
      'href',
      '/statement?team=1&period=11',
    );
    const research = within(rowOf('Team credits', 'Research')).getAllByRole('cell');
    expect(research.at(-1)).toHaveTextContent('2');
    const operations = within(rowOf('Team credits', 'Operations')).getAllByRole('cell');
    expect(operations.at(-2)).toHaveTextContent('2');
    expect(operations[3]).toHaveTextContent('800.00');

    expect(screen.getByText('Nov 1, 2026 to Nov 30, 2026', { selector: 'p' })).toBeInTheDocument();
    expect(server.callsTo('GET /api/dashboard/utilization').map((call) => call.query.get('periodId'))).toEqual(['11']);
    expect(server.callsTo('GET /api/dashboard/idle').map((call) => call.query.get('days'))).toEqual(['7']);
    expect(server.callsTo('GET /api/dashboard/credits').map((call) => call.query.get('periodId'))).toEqual(['11']);
  });

  it('opens a Team’s Statement for the Period from its row', async () => {
    const statement: Statement = {
      team: { id: 1, name: 'Platform' },
      period: november,
      opening: { granted: 0, earned: 0 },
      totals: { grants: 5000, charges: 30, earnings: 0, refunds: 22.5, expired: 0 },
      closing: { granted: 4992.5, earned: 0 },
      account: { team: 1, granted: 4992.5, earned: 0, held: 0, balance: 4992.5, available: 4992.5 },
      transactions: [],
    };
    const server = mockApi({
      'GET /api/me': session(erin),
      ...dashboardApi,
      'GET /api/teams': json([
        { id: 3, name: 'Operations' },
        { id: 1, name: 'Platform' },
        { id: 2, name: 'Research' },
      ]),
      'GET /api/ledger/statements/:team/:period': json(statement),
    });
    const { user } = renderApp('/dashboard');

    await user.click(await screen.findByRole('link', { name: 'Platform' }));

    expect(await screen.findByText('Platform Team, Nov 1, 2026 to Nov 30, 2026')).toBeInTheDocument();
    const account = screen.getByRole('region', { name: 'Account now' });
    expect(within(account).getByText('Balance').nextElementSibling).toHaveTextContent('4,992.50');
    expect(server.callsTo('GET /api/ledger/statements/:team/:period').map((call) => call.path)).toEqual([
      '/api/ledger/statements/1/11',
    ]);
  });

  it('lets the viewer pick a past Period, never a future one', async () => {
    const server = mockApi({ 'GET /api/me': session(alice), ...dashboardApi });
    const { user } = renderApp('/dashboard');

    const picker = await screen.findByLabelText('Period');
    expect(within(picker).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Nov 1, 2026 to Nov 30, 2026',
      'Oct 1, 2026 to Oct 31, 2026',
    ]);
    await user.selectOptions(picker, 'Oct 1, 2026 to Oct 31, 2026');

    await vi.waitFor(() =>
      expect(server.callsTo('GET /api/dashboard/credits').map((call) => call.query.get('periodId'))).toEqual([
        '11',
        '10',
      ]),
    );
    expect(server.callsTo('GET /api/dashboard/utilization').map((call) => call.query.get('periodId'))).toEqual([
      '11',
      '10',
    ]);
  });

  it('shows a Resource Owner the utilization of their types and no credits', async () => {
    const server = mockApi({
      'GET /api/me': session(dave),
      ...dashboardApi,
      'GET /api/dashboard/utilization': json([utilization[2]]),
    });
    renderApp('/dashboard');

    await screen.findByRole('table', { name: 'Utilization' });
    expect(within(screen.getByRole('table', { name: 'Utilization' })).getAllByRole('row')).toHaveLength(2);
    expect(screen.queryByRole('table', { name: 'Team credits' })).not.toBeInTheDocument();
    expect(server.callsTo('GET /api/dashboard/credits')).toHaveLength(0);
    expect(server.callsTo('GET /api/dashboard/person-days')).toHaveLength(0);
    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument();
  });

  it('compares requested with used unit-hours per Team and Metered type for the chosen Period (dsh-usage)', async () => {
    const server = mockApi({
      'GET /api/me': session(erin),
      ...dashboardApi,
      'GET /api/dashboard/usage': (request) => json(request.query.get('periodId') === '10' ? [gpuUsage] : []),
    });
    const { user } = renderApp('/dashboard');

    expect(await screen.findByText('No Metered allocations in your view for this Period.')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Period'), 'Oct 1, 2026 to Oct 31, 2026');

    await screen.findByRole('table', { name: 'Requested vs used' });
    const cells = within(rowOf('Requested vs used', 'Platform')).getAllByRole('cell');
    expect(cells.map((cell) => cell.textContent)).toEqual(['Platform', 'GPU', '8.00', '6.00', '1', '0']);
    expect(server.callsTo('GET /api/dashboard/usage').map((call) => call.query.get('periodId'))).toEqual([
      '11',
      '10',
    ]);
  });

  it('shows a refused comparison in its own section and keeps the rest of the dashboard', async () => {
    mockApi({
      'GET /api/me': session(alice),
      ...dashboardApi,
      'GET /api/dashboard/usage': problem(403, 'FORBIDDEN', 'You may not see this.'),
    });
    renderApp('/dashboard');

    expect(await screen.findByText('You may not see this.')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Utilization' })).toBeInTheDocument();
  });

  it('says so when no type is in view', async () => {
    mockApi({ 'GET /api/me': session(dave), ...dashboardApi, 'GET /api/dashboard/utilization': json([]) });
    renderApp('/dashboard');

    expect(await screen.findByText('No Exclusive or Metered types in your view.')).toBeInTheDocument();
  });

  it('is not offered to a Member with no other role', async () => {
    mockApi({ 'GET /api/me': session(ben) });
    renderApp('/dashboard');

    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument();
  });
});
