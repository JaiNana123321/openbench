import type { Feature } from '../../shell/feature';
import { EscalationsPage } from './EscalationsPage';

/** The Capacity Planner's escalations: approvals outstanding past the Objection Window. */
export const escalationsFeature: Feature = {
  routes: [{ path: '/escalations', element: <EscalationsPage />, roles: ['PLANNER'] }],
  nav: [
    {
      to: '/escalations',
      label: 'Escalations',
      description: 'Approve or refuse the approvals a lending Manager left outstanding past the Objection Window.',
      roles: ['PLANNER'],
    },
  ],
};
