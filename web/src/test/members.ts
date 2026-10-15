import type { Me } from '../api/types';

// Members of the demo world (.scratch/demo1/demo-world.md) as GET /api/me returns them.
// The ids are made up; the seed assigns its own.

export const alice: Me = {
  id: 1,
  name: 'Alice Chen',
  username: 'alice',
  team: { id: 1, name: 'Platform' },
  roles: ['MANAGER', 'MEMBER'],
};

export const ben: Me = {
  id: 2,
  name: 'Ben Ortiz',
  username: 'ben',
  team: { id: 1, name: 'Platform' },
  roles: ['MEMBER'],
};

export const carol: Me = {
  id: 6,
  name: 'Carol Davis',
  username: 'carol',
  team: { id: 3, name: 'Operations' },
  roles: ['ADMIN', 'MEMBER'],
};

export const dave: Me = {
  id: 7,
  name: 'Dave Kim',
  username: 'dave',
  team: { id: 3, name: 'Operations' },
  roles: ['RESOURCE_OWNER', 'MEMBER'],
};
