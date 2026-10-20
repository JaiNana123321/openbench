import type { Feature } from '../../shell/feature';
import { RequestPage } from './RequestPage';
import { RequestsPage } from './RequestsPage';

export const requestsFeature: Feature = {
  routes: [
    { path: '/requests', element: <RequestsPage />, roles: ['MANAGER', 'PLANNER'] },
    { path: '/requests/:id', element: <RequestPage />, roles: ['MANAGER', 'PLANNER'] },
  ],
  nav: [
    {
      to: '/requests',
      label: 'Requests',
      description: 'Draft your Team’s Requests and see each line’s Quote against the Available Balance.',
      roles: ['MANAGER', 'PLANNER'],
    },
  ],
};
