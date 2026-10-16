import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { alice } from '../test/members';
import { json, mockApi, problem, session } from '../test/mockApi';
import { renderApp } from '../test/renderApp';

describe('LoginPage', () => {
  it('gets /api/me first, posts a form-encoded body with the CSRF header, and goes home', async () => {
    let signedIn = false;
    const server = mockApi({
      'GET /api/me': () => session(signedIn ? alice : null)(),
      'POST /api/login': () => {
        signedIn = true;
        return json(alice);
      },
    });
    const { user } = renderApp('/login');

    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'demo');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Welcome, Alice Chen' })).toBeInTheDocument();
    expect(server.calls.slice(0, 2).map((call) => `${call.method} ${call.path}`)).toEqual([
      'GET /api/me',
      'POST /api/login',
    ]);
    const [login] = server.callsTo('POST /api/login');
    expect(login.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(login.body).toBe('username=alice&password=demo');
    expect(login.headers.get('X-XSRF-TOKEN')).toBe('csrf-token-1');
  });

  it('shows the detail of a 401 and stays on the page', async () => {
    mockApi({
      'GET /api/me': session(null),
      'POST /api/login': problem(401, 'BAD_CREDENTIALS', 'The username or password is wrong.'),
    });
    const { user, router } = renderApp('/login');

    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'not-demo');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('The username or password is wrong.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('returns to the page the guard sent the visitor from', async () => {
    let signedIn = false;
    mockApi({
      'GET /api/me': () => session(signedIn ? alice : null)(),
      'POST /api/login': () => {
        signedIn = true;
        return json(alice);
      },
    });
    const { user, router } = renderApp('/no-such-page');

    await user.type(await screen.findByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'demo');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/no-such-page');
  });
});
