import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { MockReply } from '../../test/mockApi';
import { ben, dave } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { gpu, gpuNodeA, meetingRoom, roomA } from './fixtures';

function serve(me = dave, routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/inventory/resources/:id': (request) => json(request.params.id === '24' ? gpuNodeA : roomA),
    'GET /api/inventory/types/:id': (request) => json(request.params.id === '4' ? gpu : meetingRoom),
    ...routes,
  });
}

describe('ResourcePage', () => {
  it('shows the Resource to any Member, without the owner’s actions', async () => {
    serve(ben);
    renderApp('/inventory/resources/21');

    expect(await screen.findByRole('heading', { name: 'Room A' })).toBeInTheDocument();
    expect(screen.getByText('Dave Kim', { selector: 'dd' })).toBeInTheDocument();
    expect(screen.getByText('North')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retire/ })).not.toBeInTheDocument();
  });

  it('lets the owner rename the Resource and change its attributes', async () => {
    const server = serve(dave, { 'PUT /api/inventory/resources/:id': json({ ...roomA, name: 'Room A1' }) });
    const { user } = renderApp('/inventory/resources/21');

    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Room A1');
    await user.selectOptions(screen.getByLabelText('wing'), 'South');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await screen.findByRole('button', { name: 'Save changes' });
    const [put] = server.callsTo('PUT /api/inventory/resources/:id');
    expect(JSON.parse(put.body ?? '')).toEqual({ name: 'Room A1', attributes: { seats: '8', wing: 'South' } });
    expect(screen.queryByRole('spinbutton', { name: 'Units' })).not.toBeInTheDocument();
  });

  it('names the earliest blocking hour when a reduction is refused', async () => {
    const server = serve(dave, {
      'PUT /api/inventory/resources/:id/units': problem(
        422,
        'UNITS_BELOW_USAGE',
        'Allocated and held units of GPU would exceed the new Pool Capacity from Thu 15 Oct 2026, 14:00.',
        { earliestHour: '2026-10-15T14:00:00-04:00' },
      ),
    });
    const { user } = renderApp('/inventory/resources/24');

    const units = await screen.findByRole('spinbutton', { name: 'Units' });
    await user.clear(units);
    await user.type(units, '4');
    await user.click(screen.getByRole('button', { name: 'Set units' }));

    expect(
      await screen.findByText(
        'Allocated and held units of GPU would exceed the new Pool Capacity from Thu 15 Oct 2026, 14:00.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('Earliest blocking hour: Thu Oct 15, 14:00')).toBeInTheDocument();
    const [put] = server.callsTo('PUT /api/inventory/resources/:id/units');
    expect(JSON.parse(put.body ?? '')).toEqual({ units: 4 });
  });

  it('shows why a retirement is refused', async () => {
    serve(dave, {
      'POST /api/inventory/resources/:id/retire': problem(422, 'RESOURCE_IN_USE', 'Room A is held or allocated.', {
        earliestHour: '2026-10-27T14:00:00-04:00',
      }),
    });
    const { user } = renderApp('/inventory/resources/21');

    await user.click(await screen.findByRole('button', { name: 'Retire Room A' }));

    expect(await screen.findByText('Room A is held or allocated.')).toBeInTheDocument();
    expect(screen.getByText('Earliest blocking hour: Tue Oct 27, 14:00')).toBeInTheDocument();
  });
});
