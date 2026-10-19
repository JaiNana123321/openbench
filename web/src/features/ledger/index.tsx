import type { Feature } from '../../shell/feature';
import { StatementPage } from './StatementPage';

export const ledgerFeature: Feature = {
  routes: [{ path: '/statement', element: <StatementPage />, roles: ['MANAGER', 'PLANNER'] }],
  nav: [
    {
      to: '/statement',
      label: 'Statement',
      description: 'A Team’s Account and every Transaction of a Period.',
      roles: ['MANAGER', 'PLANNER'],
    },
  ],
};
