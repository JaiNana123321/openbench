import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ResourceType } from '../../api/types';
import { carol, dave } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const meetingRoom: ResourceType = {
  id: 2,
  name: 'Meeting room',
  allocationClass: 'EXCLUSIVE',
  attributes: [
    { id: 11, name: 'seats', kind: 'NUMBER', allowedValues: null },
    { id: 12, name: 'wing', kind: 'LIST', allowedValues: ['North', 'South'] },
  ],
  retired: false,
  poolCapacity: null,
};

const printer: ResourceType = {
  id: 5,
  name: '3D printer',
  allocationClass: 'EXCLUSIVE',
  attributes: [{ id: 15, name: 'material', kind: 'LIST', allowedValues: ['PLA', 'ABS'] }],
  retired: false,
  poolCapacity: null,
};

const retiredLab: ResourceType = { ...meetingRoom, id: 3, name: 'Old lab', attributes: [], retired: true };

function serve(routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(carol),
    'GET /api/inventory/types': json([meetingRoom, retiredLab]),
    'GET /api/inventory/types/:id': json(meetingRoom),
    ...routes,
  });
}

describe('Resource Types administration', () => {
  it('lists every type, retired ones included', async () => {
    const server = serve();
    renderApp('/admin/types');

    const table = await screen.findByRole('table', { name: 'Resource Types' });
    expect(within(table).getByRole('row', { name: /Old lab/ })).toHaveTextContent('Retired');
    expect(within(table).getByRole('row', { name: /Meeting room/ })).toHaveTextContent('seats (Number), wing (List)');
    expect(server.callsTo('GET /api/inventory/types')[0].query.get('includeRetired')).toBe('true');
  });

  it('defines "3D printer" with a list attribute, as data', async () => {
    const server = serve({ 'POST /api/inventory/types': json(printer, 201) });
    const { user, router } = renderApp('/admin/types/new');

    await user.type(await screen.findByLabelText('Name'), '3D printer');
    await user.selectOptions(screen.getByLabelText(/^Allocation Class/), 'EXCLUSIVE');
    await user.click(screen.getByRole('button', { name: 'Add attribute' }));
    const attribute = screen.getByRole('group', { name: 'Attribute 1' });
    await user.type(within(attribute).getByLabelText('Name'), 'material');
    await user.selectOptions(within(attribute).getByLabelText('Kind'), 'LIST');
    await user.type(within(attribute).getByLabelText(/^Allowed values/), 'PLA, ABS');
    await user.click(screen.getByRole('button', { name: 'Define type' }));

    expect(await screen.findByRole('heading', { name: 'Resource Types' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin/types');
    const [post] = server.callsTo('POST /api/inventory/types');
    expect(JSON.parse(post.body ?? '')).toEqual({
      name: '3D printer',
      allocationClass: 'EXCLUSIVE',
      attributes: [{ name: 'material', kind: 'LIST', allowedValues: ['PLA', 'ABS'] }],
    });
  });

  it('shows a refused definition as the server words it', async () => {
    serve({
      'POST /api/inventory/types': problem(422, 'TYPE_NAME_TAKEN', 'A Resource Type named GPU already exists.'),
    });
    const { user } = renderApp('/admin/types/new');

    await user.type(await screen.findByLabelText('Name'), 'GPU');
    await user.click(screen.getByRole('button', { name: 'Define type' }));

    expect(await screen.findByText('A Resource Type named GPU already exists.')).toBeInTheDocument();
  });

  it('changes a type, keeping attribute ids and leaving out a removed attribute', async () => {
    const server = serve({ 'PUT /api/inventory/types/:id': json(meetingRoom) });
    const { user } = renderApp('/admin/types/2');

    await screen.findByRole('group', { name: 'Attribute 2' });
    expect(within(screen.getByRole('group', { name: 'Attribute 2' })).getByLabelText(/^Allowed values/)).toHaveValue(
      'North, South',
    );
    await user.click(screen.getByRole('button', { name: 'Remove attribute 1' }));
    const wing = screen.getByRole('group', { name: 'Attribute 1' });
    await user.type(within(wing).getByLabelText(/^Allowed values/), ', East');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await screen.findByRole('heading', { name: 'Resource Types' });
    const [put] = server.callsTo('PUT /api/inventory/types/:id');
    expect(put.path).toBe('/api/inventory/types/2');
    expect(JSON.parse(put.body ?? '')).toEqual({
      name: 'Meeting room',
      allocationClass: 'EXCLUSIVE',
      attributes: [{ id: 12, name: 'wing', kind: 'LIST', allowedValues: ['North', 'South', 'East'] }],
    });
  });

  it('shows why a retirement is refused', async () => {
    serve({
      'POST /api/inventory/types/:id/retire': problem(422, 'PERSON_TYPE_FIXED', 'The built-in Person type cannot be retired.'),
    });
    const { user } = renderApp('/admin/types/2');

    await user.click(await screen.findByRole('button', { name: 'Retire Meeting room' }));

    expect(await screen.findByText('The built-in Person type cannot be retired.')).toBeInTheDocument();
  });

  it('is not open to a Member who is not an Administrator', async () => {
    mockApi({ 'GET /api/me': session(dave) });
    renderApp('/admin/types');

    expect(await screen.findByRole('heading', { name: /not allowed/i })).toBeInTheDocument();
  });
});
