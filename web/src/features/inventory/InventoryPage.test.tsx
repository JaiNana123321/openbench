import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ben, dave } from '../../test/members';
import { json, mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { gpuNodeA, page, roomA, types } from './fixtures';

function serve(me = ben) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/inventory/types': json(types),
    'GET /api/inventory/resources': (request) =>
      json(page(request.query.get('type') === '4' ? [gpuNodeA] : [gpuNodeA, roomA])),
  });
}

describe('InventoryPage', () => {
  it('shows every type with its class and Pool Capacity, and the Resources', async () => {
    serve();
    renderApp('/inventory');

    const typeTable = await screen.findByRole('table', { name: 'Resource Types' });
    const gpuRow = within(typeTable).getByRole('row', { name: /GPU/ });
    expect(within(gpuRow).getByText('Metered')).toBeInTheDocument();
    expect(within(gpuRow).getByText('16')).toBeInTheDocument();

    const resources = await screen.findByRole('table', { name: 'Resources' });
    expect(within(resources).getByRole('link', { name: 'Room A' })).toHaveAttribute('href', '/inventory/resources/21');
    expect(within(resources).getByText('seats 8 · wing North')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Add Resource' })).not.toBeInTheDocument();
  });

  it('filters the Resources by type', async () => {
    const server = serve();
    const { user } = renderApp('/inventory');

    await screen.findByRole('table', { name: 'Resources' });
    await user.selectOptions(screen.getByLabelText('Type'), 'GPU');

    const resources = await screen.findByRole('table', { name: 'Resources' });
    expect(await within(resources).findAllByRole('link')).toHaveLength(1);
    expect(server.callsTo('GET /api/inventory/resources').at(-1)?.query.get('type')).toBe('4');
  });

  it('offers a Resource Owner to add a Resource', async () => {
    serve(dave);
    renderApp('/inventory');

    expect(await screen.findByRole('link', { name: 'Add Resource' })).toHaveAttribute(
      'href',
      '/inventory/resources/new',
    );
  });
});
