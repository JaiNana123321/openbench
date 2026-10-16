import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Me } from '../api/types';
import { homeFeature } from '../features/home';
import { alice, ben, carol } from '../test/members';
import { mockApi, noContent, session } from '../test/mockApi';
import { renderApp } from '../test/renderApp';
import type { Feature } from './feature';

// Two stand-in features with role-restricted pages, as later screens will have.
const requestsFeature: Feature = {
  routes: [{ path: '/requests', element: <p>The Requests page</p>, roles: ['MANAGER', 'PLANNER'] }],
  nav: [{ to: '/requests', label: 'Requests', description: 'Your Team’s Requests.', roles: ['MANAGER', 'PLANNER'] }],
};
const typesFeature: Feature = {
  routes: [{ path: '/admin/types', element: <p>The Resource Types page</p>, roles: ['ADMIN'] }],
  nav: [{ to: '/admin/types', label: 'Resource Types', description: 'Define Resource Types.', roles: ['ADMIN'] }],
};
const features = [homeFeature, requestsFeature, typesFeature];

function renderAs(me: Me, path = '/') {
  const server = mockApi({ 'GET /api/me': session(me), 'POST /api/logout': noContent() });
  return { server, ...renderApp(path, { features }) };
}

describe('AppShell', () => {
  it.each([
    ['alice, a Manager', alice, ['Home', 'Requests']],
    ['carol, an Administrator', carol, ['Home', 'Resource Types']],
    ['ben, a Member only', ben, ['Home']],
  ])('shows %s only the menu items of their roles', async (_, me, expected) => {
    renderAs(me);

    const menu = await screen.findByRole('navigation', { name: 'Main' });
    const items = within(menu).getAllByRole('link').map((link) => link.textContent);
    expect(items).toEqual(expected);
  });

  it('shows the signed-in Member and their Team in the header', async () => {
    renderAs(alice);

    const header = await screen.findByRole('banner');
    expect(within(header).getByText('Alice Chen')).toBeInTheDocument();
    expect(within(header).getByText('Platform')).toBeInTheDocument();
  });

  it('opens a page the roles allow', async () => {
    renderAs(alice, '/requests');
    expect(await screen.findByText('The Requests page')).toBeInTheDocument();
  });

  it('shows Not allowed for a page the roles do not allow', async () => {
    renderAs(alice, '/admin/types');

    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
    expect(screen.queryByText('The Resource Types page')).not.toBeInTheDocument();
  });

  it('shows Page not found for an unknown address', async () => {
    renderAs(alice, '/no-such-page');

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });

  it('signs out with the CSRF header, clears the query cache, and returns to /login', async () => {
    const { server, user, router, queryClient } = renderAs(alice);

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(server.callsTo('POST /api/logout')[0].headers.get('X-XSRF-TOKEN')).toBe('csrf-token-1');
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
  });
});
