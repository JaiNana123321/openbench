import type { DraftView, RequestDetail, RequestEvent, RequestLine, RequestSummary } from '../../api/types';

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

/** Alice's Request once submitted: Allocated, Room A assigned, both Quotes locked. */
export const aliceAllocated: RequestDetail = {
  request: { ...aliceDraft.request, state: 'ALLOCATED', submittedAt: '2026-10-23T10:05:00-04:00' },
  lines: [
    {
      line: { ...roomLine, state: 'ALLOCATED' },
      assignedResources: [{ id: 21, name: 'Room A' }],
      reason: null,
      reasonMessage: null,
      waitlistPosition: null,
    },
    { line: { ...gpuLine, state: 'ALLOCATED' }, assignedResources: [], reason: null, reasonMessage: null, waitlistPosition: null },
  ],
  history: [
    event(null, null, 'DRAFT', null, '2026-10-23T10:00:00-04:00'),
    event(null, 'DRAFT', 'SUBMITTED', 'Quotes locked.'),
    event(101, 'DRAFT', 'ALLOCATED', 'Charged 30.00 credits.'),
    event(102, 'DRAFT', 'ALLOCATED', 'Charged 1120.00 credits.'),
    event(null, 'SUBMITTED', 'ALLOCATED', null),
  ],
};

/** Bob's Request of the demo story: Room A at an overlapping time and 10 GPU units, both waiting. */
export const bobWaiting: RequestDetail = {
  request: {
    id: 43,
    team: { id: 2, name: 'Research' },
    state: 'WAITING',
    createdAt: '2026-10-23T10:10:00-04:00',
    submittedAt: '2026-10-23T10:15:00-04:00',
  },
  lines: [
    {
      line: {
        ...roomLine,
        id: 111,
        requestId: 43,
        window: { start: '2026-10-27T15:00:00-04:00', end: '2026-10-27T17:00:00-04:00' },
        state: 'WAITING',
      },
      assignedResources: [],
      reason: 'NO_FREE_INSTANCE',
      reasonMessage: 'Room A is taken for part of the window.',
      waitlistPosition: 1,
    },
    {
      line: {
        ...gpuLine,
        id: 112,
        requestId: 43,
        quantity: 10,
        window: { start: '2026-10-27T10:00:00-04:00', end: '2026-10-27T14:00:00-04:00' },
        state: 'WAITING',
        quote: 300,
      },
      assignedResources: [],
      reason: 'QUOTA_EXCEEDED',
      reasonMessage: 'The line exceeds the Team’s Quota of 8 GPU units by 2 units.',
      waitlistPosition: 2,
    },
  ],
  history: [
    event(null, null, 'DRAFT', null, '2026-10-23T10:10:00-04:00', 'Bob Patel'),
    event(null, 'DRAFT', 'SUBMITTED', 'Quotes locked.', undefined, 'Bob Patel'),
    event(111, 'DRAFT', 'WAITING', 'NO_FREE_INSTANCE: Room A is taken for part of the window.', undefined, 'Bob Patel'),
    event(112, 'DRAFT', 'WAITING', 'QUOTA_EXCEEDED: The line exceeds the Team’s Quota.', undefined, 'Bob Patel'),
    event(null, 'SUBMITTED', 'WAITING', '2 Required lines wait for capacity.', undefined, 'Bob Patel'),
  ],
};

function event(
  line: number | null,
  fromState: string | null,
  toState: string,
  note: string | null,
  at = '2026-10-23T10:05:00-04:00',
  actorName = 'Alice Chen',
): RequestEvent {
  return { at, line, fromState, toState, actor: 1, actorName, note };
}

export const aliceDraftSummary: RequestSummary = {
  id: 42,
  team: { id: 1, name: 'Platform' },
  state: 'DRAFT',
  createdAt: '2026-10-23T10:00:00-04:00',
  submittedAt: null,
  lineCount: 2,
  requiredSum: 1150,
};
