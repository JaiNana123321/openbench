import type { DraftView, RequestLine, RequestSummary } from '../../api/types';

// Alice's Draft of the demo world (.scratch/demo1/demo-world.md) as the API returns it: Room A on
// Tue Oct 27, 14:00 to 16:00 (30.00) and 4 GPU units from Tue 09:00 to Thu 09:00 (1120.00), against
// Platform's 5000.00. Ids are made up; the type and Resource ids match the Inventory fixtures.

export const roomLine: RequestLine = {
  id: 101,
  requestId: 42,
  typeId: 2,
  typeName: 'Meeting room',
  allocationClass: 'EXCLUSIVE',
  quantity: 1,
  window: { start: '2026-10-27T14:00:00-04:00', end: '2026-10-27T16:00:00-04:00' },
  namedResource: 21,
  namedResourceName: 'Room A',
  required: true,
  state: 'DRAFT',
  quote: 30,
};

export const gpuLine: RequestLine = {
  id: 102,
  requestId: 42,
  typeId: 4,
  typeName: 'GPU',
  allocationClass: 'METERED',
  quantity: 4,
  window: { start: '2026-10-27T09:00:00-04:00', end: '2026-10-29T09:00:00-04:00' },
  namedResource: null,
  namedResourceName: null,
  required: true,
  state: 'DRAFT',
  quote: 1120,
};

export const aliceDraft: DraftView = {
  request: {
    id: 42,
    team: { id: 1, name: 'Platform' },
    state: 'DRAFT',
    createdAt: '2026-10-23T10:00:00-04:00',
    submittedAt: null,
  },
  lines: [roomLine, gpuLine],
  requiredSum: 1150,
  optionalSum: 0,
  availableBalance: 5000,
  shortfall: 0,
};

export const aliceDraftSummary: RequestSummary = {
  id: 42,
  team: { id: 1, name: 'Platform' },
  state: 'DRAFT',
  createdAt: '2026-10-23T10:00:00-04:00',
  submittedAt: null,
  lineCount: 2,
  requiredSum: 1150,
};
