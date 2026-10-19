import type { Resource, ResourceType } from '../../api/types';

// The demo world's Inventory (.scratch/demo1/demo-world.md) as the API returns it. Ids are made up.

export const person: ResourceType = {
  id: 1,
  name: 'Person',
  allocationClass: 'MATCHED',
  attributes: [],
  retired: false,
  poolCapacity: null,
};

export const meetingRoom: ResourceType = {
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

export const gpu: ResourceType = {
  id: 4,
  name: 'GPU',
  allocationClass: 'METERED',
  attributes: [{ id: 14, name: 'model', kind: 'LIST', allowedValues: ['A100', 'H100'] }],
  retired: false,
  poolCapacity: 16,
};

export const types = [gpu, meetingRoom, person];

export const roomA: Resource = {
  id: 21,
  typeId: 2,
  typeName: 'Meeting room',
  name: 'Room A',
  ownerId: 7,
  ownerName: 'Dave Kim',
  units: 1,
  attributes: { seats: '8', wing: 'North' },
  retired: false,
};

export const gpuNodeA: Resource = {
  id: 24,
  typeId: 4,
  typeName: 'GPU',
  name: 'gpu-node-a',
  ownerId: 7,
  ownerName: 'Dave Kim',
  units: 8,
  attributes: { model: 'H100' },
  retired: false,
};

export function page<T>(content: T[]) {
  return { content, page: 0, size: 50, totalElements: content.length };
}
