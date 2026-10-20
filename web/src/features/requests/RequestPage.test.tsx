import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DraftView } from '../../api/types';
import { alice, erin } from '../../test/members';
import { json, mockApi, noContent, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { roomA, types } from '../inventory/fixtures';
import { aliceDraft, gpuLine, roomLine } from './fixtures';

function serve(draft: DraftView = aliceDraft, me = alice, routes = {}) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/requests/:id/draft': json(draft),
    'GET /api/inventory/types': json(types),
    'GET /api/inventory/resources': json({ content: [roomA], page: 0, size: 200, totalElements: 1 }),
    'POST /api/requests/:id/lines': json(roomLine, 201),
    ...routes,
  });
}

/** The type picker, once the types have loaded. */
async function typeSelect() {
  await screen.findByRole('option', { name: 'GPU (Metered)' });
  return screen.getByLabelText('Resource Type');
}

describe('RequestPage, the Draft editor', () => {
  it('shows each line’s Quote and the totals as the server computed them', async () => {
    serve();
    renderApp('/requests/42');

    const lines = await screen.findByRole('table', { name: 'Lines' });
    const rows = within(lines).getAllByRole('row').slice(1);
    expect(within(rows[0]).getByText('Room A')).toBeInTheDocument();
    expect(within(rows[0]).getByText('Tue Oct 27, 14:00 to 16:00')).toBeInTheDocument();
    expect(within(rows[0]).getByText('30.00')).toBeInTheDocument();
    expect(within(rows[1]).getByText('4 units')).toBeInTheDocument();
    expect(within(rows[1]).getByText('1,120.00')).toBeInTheDocument();

    const totals = screen.getByRole('complementary', { name: 'Totals' });
    expect(within(totals).getByText('Required lines').nextSibling).toHaveTextContent('1,150.00');
    expect(within(totals).getByText('Optional lines').nextSibling).toHaveTextContent('0.00');
    expect(within(totals).getByText('Available Balance').nextSibling).toHaveTextContent('5,000.00');
    expect(within(totals).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('warns of the shortfall the server reports', async () => {
    serve({ ...aliceDraft, availableBalance: 1000, shortfall: 150 });
    renderApp('/requests/42');

    const totals = await screen.findByRole('complementary', { name: 'Totals' });
    expect(within(totals).getByRole('alert')).toHaveTextContent('Short by 150.00 credits');
  });

  it('offers Exclusive and Metered types only, and sends the line as the API takes it', async () => {
    const server = serve();
    const { user } = renderApp('/requests/42');

    const select = await typeSelect();
    expect(within(select).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Choose a type',
      'GPU (Metered)',
      'Meeting room (Exclusive)',
    ]);

    await user.selectOptions(select, 'Meeting room (Exclusive)');
    await user.selectOptions(await screen.findByLabelText(/^Specific Resource/), 'Room A');
    fireEvent.change(screen.getByLabelText(/^Start/), { target: { value: '2026-10-27T14:00' } });
    fireEvent.change(screen.getByLabelText(/^End/), { target: { value: '2026-10-27T16:00' } });
    await user.click(screen.getByRole('button', { name: 'Add line' }));

    await screen.findByRole('table', { name: 'Lines' });
    const [post] = server.callsTo('POST /api/requests/:id/lines');
    expect(post.path).toBe('/api/requests/42/lines');
    expect(JSON.parse(post.body ?? '')).toEqual({
      typeId: 2,
      quantity: 1,
      start: '2026-10-27T14:00:00-04:00',
      end: '2026-10-27T16:00:00-04:00',
      namedResourceId: 21,
    });
  });

  it('asks for units for a Metered type and names no Resource', async () => {
    const server = serve();
    const { user } = renderApp('/requests/42');

    await user.selectOptions(await typeSelect(), 'GPU (Metered)');
    expect(screen.queryByLabelText(/^Specific Resource/)).not.toBeInTheDocument();
    const units = screen.getByLabelText(/^Units/);
    await user.clear(units);
    await user.type(units, '4');
    fireEvent.change(screen.getByLabelText(/^Start/), { target: { value: '2026-10-27T09:00' } });
    fireEvent.change(screen.getByLabelText(/^End/), { target: { value: '2026-10-29T09:00' } });
    await user.click(screen.getByRole('button', { name: 'Add line' }));

    await screen.findByRole('table', { name: 'Lines' });
    const [post] = server.callsTo('POST /api/requests/:id/lines');
    expect(JSON.parse(post.body ?? '')).toEqual({
      typeId: 4,
      quantity: 4,
      start: '2026-10-27T09:00:00-04:00',
      end: '2026-10-29T09:00:00-04:00',
    });
  });

  it('shows a refused line as the server words it', async () => {
    serve(aliceDraft, alice, {
      'POST /api/requests/:id/lines': problem(422, 'WINDOW_NOT_FUTURE', 'The window must start in the future.'),
    });
    const { user } = renderApp('/requests/42');

    await user.selectOptions(await typeSelect(), 'GPU (Metered)');
    fireEvent.change(screen.getByLabelText(/^Start/), { target: { value: '2020-10-27T09:00' } });
    fireEvent.change(screen.getByLabelText(/^End/), { target: { value: '2020-10-27T10:00' } });
    await user.click(screen.getByRole('button', { name: 'Add line' }));

    expect(await screen.findByText('The window must start in the future.')).toBeInTheDocument();
  });

  it('marks a line Optional, splits a line, and removes a line', async () => {
    const server = serve(aliceDraft, alice, {
      'PUT /api/requests/:id/lines/:lineId/required': json({ ...gpuLine, required: false }),
      'POST /api/requests/:id/lines/:lineId/split': json({ ...gpuLine, id: 103, quantity: 1, required: false }, 201),
      'DELETE /api/requests/:id/lines/:lineId': noContent(),
    });
    const { user } = renderApp('/requests/42');

    await user.click(await screen.findByRole('checkbox', { name: 'Line 2 Required' }));
    const [mark] = server.callsTo('PUT /api/requests/:id/lines/:lineId/required');
    expect(mark.path).toBe('/api/requests/42/lines/102/required');
    expect(JSON.parse(mark.body ?? '')).toEqual({ required: false });

    expect(screen.queryByRole('button', { name: 'Split line 1' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Split line 2' }));
    await user.click(screen.getByRole('button', { name: 'Move to new line' }));
    const [split] = server.callsTo('POST /api/requests/:id/lines/:lineId/split');
    expect(split.path).toBe('/api/requests/42/lines/102/split');
    expect(JSON.parse(split.body ?? '')).toEqual({ quantity: 1 });

    await user.click(screen.getByRole('button', { name: 'Remove line 1' }));
    const [removed] = server.callsTo('DELETE /api/requests/:id/lines/:lineId');
    expect(removed.path).toBe('/api/requests/42/lines/101');
  });

  it('shows a Request that is not a Draft read only', async () => {
    serve({ ...aliceDraft, request: { ...aliceDraft.request, state: 'ALLOCATED' } });
    renderApp('/requests/42');

    expect(await screen.findByRole('table', { name: 'Lines' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add line' })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument();
  });

  it('shows a Capacity Planner another Team’s Draft read only', async () => {
    serve(aliceDraft, erin);
    renderApp('/requests/42');

    expect(await screen.findByRole('table', { name: 'Lines' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add line' })).not.toBeInTheDocument();
  });
});
