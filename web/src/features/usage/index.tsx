import type { Feature } from '../../shell/feature';
import { UsagePage } from './UsagePage';

export const usageFeature: Feature = {
  routes: [{ path: '/usage', element: <UsagePage />, roles: ['RESOURCE_OWNER'] }],
  nav: [
    {
      to: '/usage',
      label: 'Metered usage',
      description: 'Record the units each Metered allocation of your types used in an ended Period.',
      roles: ['RESOURCE_OWNER'],
    },
  ],
};
