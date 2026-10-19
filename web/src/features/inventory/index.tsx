import type { Feature } from '../../shell/feature';
import { AddResourcePage } from './AddResourcePage';
import { InventoryPage } from './InventoryPage';
import { ResourcePage } from './ResourcePage';

export const inventoryFeature: Feature = {
  routes: [
    { path: '/inventory', element: <InventoryPage />, roles: ['MEMBER'] },
    { path: '/inventory/resources/new', element: <AddResourcePage />, roles: ['RESOURCE_OWNER'] },
    { path: '/inventory/resources/:id', element: <ResourcePage />, roles: ['MEMBER'] },
  ],
  nav: [
    {
      to: '/inventory',
      label: 'Inventory',
      description: 'Browse every Resource Type and Resource; Resource Owners keep theirs current.',
      roles: ['MEMBER'],
    },
  ],
};
