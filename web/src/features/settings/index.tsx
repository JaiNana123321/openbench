import type { Feature } from '../../shell/feature';
import { SettingsPage } from './SettingsPage';

/** The Capacity Planner's Settings: every policy value, a mid-Period Grant, and the change history. */
export const settingsFeature: Feature = {
  routes: [{ path: '/settings', element: <SettingsPage />, roles: ['PLANNER'] }],
  nav: [
    {
      to: '/settings',
      label: 'Settings',
      description: 'Prices, Grants, Quotas, and every other policy value, with their history.',
      roles: ['PLANNER'],
    },
  ],
};
