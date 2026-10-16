import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Feature } from '../../shell/feature';
import { alice, dave } from '../../test/members';
import { mockApi, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { homeFeature } from '.';

const inventoryFeature: Feature = {
  routes: [],
  nav: [{ to: '/inventory', label: 'Inventory', description: 'Browse every Resource.', roles: ['MEMBER'] }],
};
const requestsFeature: Feature = {
  routes: [],
  nav: [{ to: '/requests', label: 'Requests', description: 'Draft and submit Requests.', roles: ['MANAGER'] }],
};
const features = [homeFeature, inventoryFeature, requestsFeature];

describe('HomePage', () => {
  it('shows who the Member is: name, Team, and roles', async () => {
    mockApi({ 'GET /api/me': session(alice) });
    renderApp('/', { features });

    expect(await screen.findByRole('heading', { name: 'Welcome, Alice Chen' })).toBeInTheDocument();
    const account = screen.getByRole('region', { name: 'Your account' });
    expect(within(account).getByText('alice')).toBeInTheDocument();
    expect(within(account).getByText('Platform')).toBeInTheDocument();
    const roles = within(account).getByRole('list', { name: 'Roles' });
    expect(within(roles).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Member',
      'Manager',
    ]);
  });

  it('links to what the Member’s roles can do', async () => {
    mockApi({ 'GET /api/me': session(dave) });
    renderApp('/', { features });

    const links = await screen.findByRole('region', { name: 'What you can do' });
    expect(within(links).getByRole('link', { name: /Inventory/ })).toHaveAttribute('href', '/inventory');
    expect(within(links).queryByRole('link', { name: /Requests/ })).not.toBeInTheDocument();
  });
});
