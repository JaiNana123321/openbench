import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Holding } from '../../api/types';
import type { MockReply } from '../../test/mockApi';
import { alice, ben, dave } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { toInstant, todayInCompanyZone } from '../../ui';
import { gpu, gpuNodeA, meetingRoom, roomA } from './fixtures';

// inv-holder: Room A on D (Wednesday, December 2, 2026), 08:00 to 18:00. A1 (Platform) has it 10:00
// to 12:00; B1 (Research) holds it 14:00 to 16:00 while it is Pending.
const platform = { id: 1, name: 'Platform' };
const research = { id: 2, name: 'Research' };

function part(from: string, to: string, rest: Partial<Holding> = {}): Holding {
  return {
    window: { start: `2026-12-02T${from}:00-05:00`, end: `2026-12-02T${to}:00-05:00` },
    state: 'FREE',
    team: null,
    request: null,
    priority: false,
    ...rest,
  };
}

const asOwner: Holding[] = [
  part('08:00', '10:00'),
  part('10:00', '12:00', { state: 'ALLOCATED', team: platform, request: 31 }),
  part('12:00', '14:00'),
  part('14:00', '16:00', { state: 'HELD', team: research, request: 32 }),
  part('16:00', '18:00'),
];

const asMember: Holding[] = asOwner.map((holding) => ({ ...holding, team: null, request: null }));

function serve(me = dave, holders: MockReply = json(asOwner), routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/inventory/resources/:id': (request) => json(request.params.id === '24' ? gpuNodeA : roomA),
    'GET /api/inventory/types/:id': (request) => json(request.params.id === '4' ? gpu : meetingRoom),
    'GET /api/inventory/resources/:id/holders': holders,
    ...routes,
  });
}

function rowsOf(table: HTMLElement) {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent));
}

describe('Resource timeline', () => {
  it('inv-holder: shows each part free, allocated, or on Hold, with the Team and Request, to the owner', async () => {
    const server = serve(dave);
    renderApp('/inventory/resources/21');

    const table = await screen.findByRole('table', { name: /^Timeline/ });
    expect(rowsOf(table)).toEqual([
      ['Wed Dec 2, 08:00 to 10:00', 'Free', '—'],
      ['Wed Dec 2, 10:00 to 12:00', 'Allocated', 'Platform, Request #31'],
      ['Wed Dec 2, 12:00 to 14:00', 'Free', '—'],
      ['Wed Dec 2, 14:00 to 16:00', 'On Hold', 'Research, Request #32'],
      ['Wed Dec 2, 16:00 to 18:00', 'Free', '—'],
    ]);
    // The Resource Owner has no Request screen, so the Requests are not links.
    expect(within(table).queryByRole('link')).not.toBeInTheDocument();

    // By default the window is today from 08:00 to 18:00, company time.
    const [call] = server.callsTo('GET /api/inventory/resources/:id/holders');
    const today = todayInCompanyZone();
    expect(call.path).toBe('/api/inventory/resources/21/holders');
    expect(call.query.get('from')).toBe(toInstant(`${today}T08:00`));
    expect(call.query.get('to')).toBe(toInstant(`${today}T18:00`));
  });

  it('shows a Member the states without the holders the server leaves out', async () => {
    serve(ben, json(asMember));
    renderApp('/inventory/resources/21');

    const table = await screen.findByRole('table', { name: /^Timeline/ });
    const rows = rowsOf(table);
    expect(rows.map((row) => row[1])).toEqual(['Free', 'Allocated', 'Free', 'On Hold', 'Free']);
    expect(rows[1][2]).toBe('Team and Request not shown to you');
    expect(rows[3][2]).toBe('Team and Request not shown to you');
  });

  it('links a Manager to their own Team’s Request and marks a Priority part', async () => {
    serve(
      alice,
      json([
        part('08:00', '10:00', { state: 'ALLOCATED', team: platform, request: 31, priority: true }),
        part('10:00', '12:00', { state: 'HELD', team: research, request: 32 }),
      ]),
    );
    renderApp('/inventory/resources/21');

    const table = await screen.findByRole('table', { name: /^Timeline/ });
    expect(within(table).getByRole('link', { name: 'Request #31' })).toHaveAttribute('href', '/requests/31');
    expect(within(table).queryByRole('link', { name: 'Request #32' })).not.toBeInTheDocument();
    expect(within(table).getByText('Priority')).toBeInTheDocument();
  });

  it('shows the timeline for another window', async () => {
    const server = serve(dave);
    const { user } = renderApp('/inventory/resources/21');

    await screen.findByRole('table', { name: /^Timeline/ });
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-12-02T08:00' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-12-02T18:00' } });
    await user.click(screen.getByRole('button', { name: 'Show timeline' }));

    expect(await screen.findByRole('table', { name: 'Timeline Wed Dec 2, 08:00 to 18:00' })).toBeInTheDocument();
    const call = server.callsTo('GET /api/inventory/resources/:id/holders').at(-1);
    expect(call?.query.get('from')).toBe('2026-12-02T08:00:00-05:00');
    expect(call?.query.get('to')).toBe('2026-12-02T18:00:00-05:00');
  });

  it('shows a refused window next to the form', async () => {
    serve(dave, problem(422, 'WINDOW_INVALID', 'The window may be at most 31 days long.'));
    renderApp('/inventory/resources/21');

    expect(await screen.findByText('The window may be at most 31 days long.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show timeline' })).toBeInTheDocument();
  });

  it('has no timeline for a Metered Resource, whose units come from the pool', async () => {
    const server = serve(dave);
    renderApp('/inventory/resources/24');

    expect(await screen.findByRole('heading', { name: 'gpu-node-a' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Timeline' })).not.toBeInTheDocument();
    expect(server.callsTo('GET /api/inventory/resources/:id/holders')).toHaveLength(0);
  });
});
