import type { Feature } from '../../shell/feature';
import { ApprovalsPage } from './ApprovalsPage';

/** A lending Manager's approvals: requests to lend the Team's Members, to approve or refuse. */
export const approvalsFeature: Feature = {
  routes: [{ path: '/approvals', element: <ApprovalsPage />, roles: ['MANAGER', 'PLANNER'] }],
  nav: [
    {
      to: '/approvals',
      label: 'Approvals',
      description: 'Approve or refuse lending your Team’s Members to other Teams, before the Objection Window ends.',
      roles: ['MANAGER'],
    },
  ],
};
