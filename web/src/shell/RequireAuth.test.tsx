import { useQuery } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { api } from '../api/client';
import { homeFeature } from '../features/home';
import { alice } from '../test/members';
import { json, mockApi, problem, session } from '../test/mockApi';
import { renderApp } from '../test/renderApp';
import type { Feature } from './feature';

/** A page that loads data, standing in for a later screen. */
function TeamsPage() {
  const teams = useQuery({ queryKey: ['teams'], queryFn: () => api.get<unknown[]>('/api/teams') });
  return <p>{teams.isSuccess ? `${teams.data.length} Teams` : 'Loading Teams'}</p>;
}

const teamsFeature: Feature = {
  routes: [{ path: '/teams', element: <TeamsPage />, roles: ['MEMBER'] }],
  nav: [],
};

describe('RequireAuth', () => {
  it('sends a visitor to /login when /api/me answers 401', async () => {
    mockApi({ 'GET /api/me': session(null) });
    const { router } = renderApp('/');

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('sends the Member to /login when a later call answers 401', async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/teams': problem(401, 'UNAUTHENTICATED', 'Sign in to continue.'),
    });
    const { router } = renderApp('/teams', { features: [homeFeature, teamsFeature] });

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('shows the page to a signed-in Member', async () => {
    mockApi({ 'GET /api/me': session(alice), 'GET /api/teams': json([{}, {}, {}]) });
    renderApp('/teams', { features: [homeFeature, teamsFeature] });

    expect(await screen.findByText('3 Teams')).toBeInTheDocument();
  });
});
