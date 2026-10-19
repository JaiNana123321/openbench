import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { dave } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { gpu, gpuNodeA, meetingRoom, roomA, types } from './fixtures';

function serve(reply = json(roomA, 201)) {
  return mockApi({
    'GET /api/me': session(dave),
    'GET /api/inventory/types': json(types),
    'GET /api/inventory/types/:id': (request) => json(request.params.id === '4' ? gpu : meetingRoom),
    'POST /api/inventory/resources': reply,
    'GET /api/inventory/resources/:id': (request) => json(request.params.id === '24' ? gpuNodeA : roomA),
  });
}

describe('AddResourcePage', () => {
  it('offers only Exclusive and Metered types', async () => {
    serve();
    renderApp('/inventory/resources/new');

    const typeSelect = await screen.findByLabelText('Resource Type');
    expect(within(typeSelect).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Choose a type',
      'GPU',
      'Meeting room',
    ]);
  });

  it('asks for the chosen type’s attributes by kind and posts them', async () => {
    const server = serve();
    const { user, router } = renderApp('/inventory/resources/new');

    await user.selectOptions(await screen.findByLabelText('Resource Type'), 'Meeting room');
    expect(screen.queryByLabelText('Units')).not.toBeInTheDocument();
    expect(screen.getByLabelText('seats')).toHaveAttribute('type', 'number');
    const wing = screen.getByLabelText('wing');
    expect(within(wing).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Not set',
      'North',
      'South',
    ]);

    await user.type(screen.getByLabelText('Name'), 'Room C');
    await user.type(screen.getByLabelText('seats'), '10');
    await user.selectOptions(wing, 'North');
    await user.click(screen.getByRole('button', { name: 'Add Resource' }));

    expect(await screen.findByRole('heading', { name: 'Room A' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/inventory/resources/21');
    const [post] = server.callsTo('POST /api/inventory/resources');
    expect(JSON.parse(post.body ?? '')).toEqual({
      typeId: 2,
      name: 'Room C',
      attributes: { seats: '10', wing: 'North' },
    });
  });

  it('asks for units for a Metered type', async () => {
    const server = serve(json(gpuNodeA, 201));
    const { user } = renderApp('/inventory/resources/new');

    await user.selectOptions(await screen.findByLabelText('Resource Type'), 'GPU');
    await user.type(screen.getByLabelText('Name'), 'gpu-node-c');
    await user.type(screen.getByLabelText(/^Units/), '4');
    await user.selectOptions(screen.getByLabelText('model'), 'A100');
    await user.click(screen.getByRole('button', { name: 'Add Resource' }));

    await screen.findByRole('heading', { name: 'gpu-node-a' });
    const [post] = server.callsTo('POST /api/inventory/resources');
    expect(JSON.parse(post.body ?? '')).toEqual({
      typeId: 4,
      name: 'gpu-node-c',
      units: 4,
      attributes: { model: 'A100' },
    });
  });

  it('shows a refusal as the server words it', async () => {
    serve(problem(422, 'RESOURCE_NAME_TAKEN', 'A Meeting room named Room A already exists.'));
    const { user } = renderApp('/inventory/resources/new');

    await user.selectOptions(await screen.findByLabelText('Resource Type'), 'Meeting room');
    await user.type(screen.getByLabelText('Name'), 'Room A');
    await user.click(screen.getByRole('button', { name: 'Add Resource' }));

    expect(await screen.findByText('A Meeting room named Room A already exists.')).toBeInTheDocument();
  });
});
