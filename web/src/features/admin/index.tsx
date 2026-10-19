import type { Feature } from '../../shell/feature';
import { ChangeTypePage, DefineTypePage, ResourceTypesPage } from './ResourceTypesPage';

/** The Administrator's screens. Demo 1 has Resource Types; Members, Teams, and skills come later. */
export const adminFeature: Feature = {
  routes: [
    { path: '/admin/types', element: <ResourceTypesPage />, roles: ['ADMIN'] },
    { path: '/admin/types/new', element: <DefineTypePage />, roles: ['ADMIN'] },
    { path: '/admin/types/:id', element: <ChangeTypePage />, roles: ['ADMIN'] },
  ],
  nav: [
    {
      to: '/admin/types',
      label: 'Resource Types',
      description: 'Define, change, and retire the kinds of Resources.',
      roles: ['ADMIN'],
    },
  ],
};
