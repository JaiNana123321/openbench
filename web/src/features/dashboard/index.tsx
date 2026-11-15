import type { Feature } from '../../shell/feature';
import { DashboardPage } from './DashboardPage';

export const dashboardFeature: Feature = {
  routes: [{ path: '/dashboard', element: <DashboardPage />, roles: ['MANAGER', 'RESOURCE_OWNER', 'PLANNER'] }],
  nav: [
    {
      to: '/dashboard',
      label: 'Dashboard',
      description: 'Utilization, idle capacity, and waiting lines per type; each Team’s credits.',
      roles: ['MANAGER', 'RESOURCE_OWNER', 'PLANNER'],
    },
  ],
};
