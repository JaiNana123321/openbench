import { describe, expect, it, vi } from 'vitest';
import { json, mockApi, noContent, problem, setCookie } from '../test/mockApi';
import { ApiError, api, fieldError } from './client';

async function failureOf(call: Promise<unknown>): Promise<ApiError> {
  try {
    await call;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('Expected the call to fail.');
}

describe('api client', () => {
  it('parses a problem+json response into an ApiError', async () => {
    mockApi({
      'POST /api/requests/:id/submit': problem(
        422,
        'INSUFFICIENT_BALANCE',
        'The Required lines need 1840.00 credits; the Available Balance is 600.00.',
        { title: 'Available Balance too low', shortfall: 1240 },
      ),
    });

    const error = await failureOf(api.post('/api/requests/42/submit'));

    expect(error).toMatchObject({
      status: 422,
      code: 'INSUFFICIENT_BALANCE',
      title: 'Available Balance too low',
      detail: 'The Required lines need 1840.00 credits; the Available Balance is 600.00.',
      errors: {},
      properties: { shortfall: 1240 },
    });
  });

  it('reads the per-field errors of a 400, as a map or as a list', async () => {
    mockApi({
      'POST /api/teams': problem(400, 'VALIDATION_FAILED', 'Some fields are not valid.', {
        errors: { name: 'must not be blank' },
      }),
      'PUT /api/teams/:id': problem(400, 'VALIDATION_FAILED', 'Some fields are not valid.', {
        errors: [{ field: 'name', message: 'must not be blank' }],
      }),
    });

    const asMap = await failureOf(api.post('/api/teams', { name: '' }));
    const asList = await failureOf(api.put('/api/teams/1', { name: '' }));

    expect(asMap).toMatchObject({ code: 'VALIDATION_FAILED', errors: { name: 'must not be blank' } });
    expect(asList).toMatchObject({ code: 'VALIDATION_FAILED', errors: { name: 'must not be blank' } });
    expect(fieldError(asList, 'name')).toBe('must not be blank');
    expect(fieldError(asList, 'units')).toBeUndefined();
  });

  it('copies the XSRF-TOKEN cookie into the X-XSRF-TOKEN header on writes only', async () => {
    setCookie('XSRF-TOKEN', 'token-abc');
    const server = mockApi({
      'GET /api/teams': json([]),
      'POST /api/teams': json({ id: 4 }, 201),
      'PUT /api/teams/:id': noContent(),
      'DELETE /api/teams/:id': noContent(),
    });

    await api.get('/api/teams');
    await api.post('/api/teams', { name: 'Imaging' });
    await api.put('/api/teams/4', { name: 'Imaging lab' });
    await api.delete('/api/teams/4');

    const tokens = server.calls.map((call) => [call.method, call.headers.get('X-XSRF-TOKEN')]);
    expect(tokens).toEqual([
      ['GET', null],
      ['POST', 'token-abc'],
      ['PUT', 'token-abc'],
      ['DELETE', 'token-abc'],
    ]);
  });

  it('sends and reads JSON, adds query parameters, and answers 204 with undefined', async () => {
    const server = mockApi({
      'GET /api/inventory/resources': json({ content: [], page: 0, size: 50, totalElements: 0 }),
      'POST /api/teams': json({ id: 4, name: 'Imaging' }, 201),
      'POST /api/logout': noContent(),
    });

    const page = await api.get('/api/inventory/resources', { type: 3, page: 0, wing: undefined });
    const team = await api.post<{ id: number }>('/api/teams', { name: 'Imaging' });
    const nothing = await api.post('/api/logout');

    expect(page).toEqual({ content: [], page: 0, size: 50, totalElements: 0 });
    expect(server.calls[0].query.toString()).toBe('type=3&page=0');
    expect(server.calls[1].headers.get('Content-Type')).toBe('application/json');
    expect(server.calls[1].body).toBe('{"name":"Imaging"}');
    expect(team).toEqual({ id: 4, name: 'Imaging' });
    expect(nothing).toBeUndefined();
  });

  it('gives an error without a problem body its status and a sentence to show', async () => {
    mockApi({ 'GET /api/me': new Response('<html>Bad gateway</html>', { status: 502 }) });

    const error = await failureOf(api.get('/api/me'));

    expect(error).toMatchObject({ status: 502, code: 'HTTP_502' });
    expect(error.detail).toMatch(/try again/i);
  });

  it('turns an unreachable server into an ApiError with status 0', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new TypeError('Failed to fetch')));

    const error = await failureOf(api.get('/api/me'));

    expect(error).toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });
});
