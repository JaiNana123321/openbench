import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { alice, ben, erin } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { aliceDraft, aliceDraftSummary } from './fixtures';

const page = { content: [aliceDraftSummary], page: 0, size: 50, totalElements: 1 };

describe('RequestsPage', () => {
  it('lists the Team’s Requests with state, lines, and the Required sum', async () => {
    mockApi({ 'GET /api/me': session(alice), 'GET /api/requests': json(page) });
    renderApp('/requests');

    const table = await screen.findByRole('table', { name: 'Requests' });
    const [row] = within(table).getAllByRole('row').slice(1);
    expect(within(row).getByRole('link', { name: 'Request #42' })).toHaveAttribute('href', '/requests/42');
    expect(within(row).getByText('Draft')).toBeInTheDocument();
    expect(within(row).getByText('2')).toBeInTheDocument();
    expect(within(row).getByText('1,150.00')).toBeInTheDocument();
  });

  it('creates a Draft and opens it', async () => {
    const server = mockApi({
      'GET /api/me': session(alice),
      'GET /api/requests': json(page),
      'POST /api/requests': json(aliceDraft.request, 201),
      'GET /api/requests/:id/draft': json(aliceDraft),
      'GET /api/inventory/types': json([]),
    });
    const { user, router } = renderApp('/requests');

    await user.click(await screen.findByRole('button', { name: 'New Request' }));

    expect(await screen.findByRole('heading', { name: 'Request #42' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/requests/42');
    expect(server.callsTo('POST /api/requests')).toHaveLength(1);
  });

  it('lets a Capacity Planner read the list without creating', async () => {
    mockApi({ 'GET /api/me': session(erin), 'GET /api/requests': json(page) });
    renderApp('/requests');

    expect(await screen.findByRole('table', { name: 'Requests' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New Request' })).not.toBeInTheDocument();
  });

  it('is in the menu for Managers and Planners only', async () => {
    mockApi({ 'GET /api/me': session(ben) });
    renderApp('/requests');

    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Requests' })).not.toBeInTheDocument();
  });
});
