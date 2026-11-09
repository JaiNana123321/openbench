import type { Feature } from '../../shell/feature';
import { NotificationsPage } from './NotificationsPage';

/** Every Member's notifications. The header links here with the unread count, so the menu has no entry. */
export const notificationsFeature: Feature = {
  routes: [{ path: '/notifications', element: <NotificationsPage />, roles: ['MEMBER'] }],
  nav: [],
};
