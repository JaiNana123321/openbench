import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Resource } from '../../api/types';
import type { MockReply } from '../../test/mockApi';
import { ben } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { page, roomA, types } from './fixtures';

// inv-filter: A1 has Room A on D (Wednesday, December 2, 2026), 10:00 to 12:00.
const roomB: Resource = { ...roomA, id: 22, name: 'Room B', attributes: { seats: '12', wing: 'South' } };

function serve(free: MockReply = (request) => json(page(request.query.get('from')?.includes('T11:') ? [roomB] : [roomA, roomB]))) {
  return mockApi({
    'GET /api/me': session(ben),
    'GET /api/inventory/types': json(types),
    'GET /api/inventory/resources': (request) =>
      json(page(request.query.get('attr.wing') === 'South' ? [roomB] : [roomA, roomB])),
    'GET /api/inventory/resources/free': free,
  });
}

async function names() {
  const table = await screen.findByRole('table', { name: 'Resources' });
  return within(table)
    .getAllByRole('link')
    .map((link) => link.textContent);
}

describe('Inventory filters', () => {
  it('inv-filter: filters by type, by attribute value, and by being free for a window', async () => {
    const server = serve();
    const { user } = renderApp('/inventory');

    // 1. Meeting room.
    await screen.findByRole('table', { name: 'Resources' });
    await user.selectOptions(screen.getByLabelText('Type'), 'Meeting room');
    expect(await names()).toEqual(['Room A', 'Room B']);

    // 2. wing = South.
    await user.selectOptions(screen.getByLabelText('wing'), 'South');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    expect(await screen.findByRole('link', { name: 'Room B' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Room A' })).not.toBeInTheDocument();
    const filtered = server.callsTo('GET /api/inventory/resources').at(-1);
    expect(filtered?.query.get('type')).toBe('2');
    expect(filtered?.query.get('attr.wing')).toBe('South');

    // 3. Clear the attribute; free D 11:00 to 13:00: Room B only.
    await user.selectOptions(screen.getByLabelText('wing'), '');
    fireEvent.change(screen.getByLabelText('Free from'), { target: { value: '2026-12-02T11:00' } });
    fireEvent.change(screen.getByLabelText('Free to'), { target: { value: '2026-12-02T13:00' } });
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    await screen.findByRole('link', { name: 'Room B' });
    expect(await names()).toEqual(['Room B']);
    const free = server.callsTo('GET /api/inventory/resources/free').at(-1);
    expect(free?.query.get('type')).toBe('2');
    expect(free?.query.get('from')).toBe('2026-12-02T11:00:00-05:00');
    expect(free?.query.get('to')).toBe('2026-12-02T13:00:00-05:00');
    expect(free?.query.has('attr.wing')).toBe(false);

    // 4. D 12:00 to 14:00: Room A and Room B.
    fireEvent.change(screen.getByLabelText('Free from'), { target: { value: '2026-12-02T12:00' } });
    fireEvent.change(screen.getByLabelText('Free to'), { target: { value: '2026-12-02T14:00' } });
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    expect(await screen.findByRole('link', { name: 'Room A' })).toBeInTheDocument();
    expect(await names()).toEqual(['Room A', 'Room B']);
  });

  it('clears the filters back to the type alone', async () => {
    const server = serve();
    const { user } = renderApp('/inventory?type=2&attr.wing=South&from=2026-12-02T11%3A00%3A00-05%3A00&to=2026-12-02T13%3A00%3A00-05%3A00');

    expect(await screen.findByLabelText('wing')).toHaveValue('South');
    expect(screen.getByLabelText('Free from')).toHaveValue('2026-12-02T11:00');
    expect(server.callsTo('GET /api/inventory/resources/free').at(-1)?.query.get('attr.wing')).toBe('South');

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(await screen.findByLabelText('Free from')).toHaveValue('');
    const list = server.callsTo('GET /api/inventory/resources').at(-1);
    expect(list?.query.get('type')).toBe('2');
    expect(list?.query.has('attr.wing')).toBe(false);
  });

  it('shows a refused window instead of the list', async () => {
    serve(problem(422, 'WINDOW_INVALID', 'The window must end after it starts.'));
    renderApp('/inventory?type=2&from=2026-12-02T13%3A00%3A00-05%3A00&to=2026-12-02T11%3A00%3A00-05%3A00');

    expect(await screen.findByText('The window must end after it starts.')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Resources' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Apply filters' })).toBeInTheDocument();
  });

  it('offers the free filter only for an Exclusive type', async () => {
    serve();
    const { user } = renderApp('/inventory');

    await screen.findByRole('table', { name: 'Resources' });
    expect(screen.queryByLabelText('Free from')).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Type'), 'GPU');
    expect(await screen.findByLabelText('model')).toBeInTheDocument();
    expect(screen.queryByLabelText('Free from')).not.toBeInTheDocument();
  });
});
