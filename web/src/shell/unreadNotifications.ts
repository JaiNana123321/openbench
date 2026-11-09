import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { MemberNotification, Page } from '../api/types';

/** The query key of the signed-in Member's unread notifications; marking one read invalidates it. */
export const notificationKeys = { unread: ['notifications'] as const };

/** How often the header asks again, so a notice raised by someone else shows up without a reload. */
export const UNREAD_REFRESH_MS = 30_000;

/**
 * The signed-in Member's unread notifications, newest first (GET /api/notifications), refreshed
 * every 30 seconds. The header shows the count; the notifications page lists them.
 */
export function useUnreadNotifications() {
  return useQuery({
    queryKey: notificationKeys.unread,
    queryFn: () => api.get<Page<MemberNotification>>('/api/notifications', { page: 0, size: 50 }),
    refetchInterval: UNREAD_REFRESH_MS,
  });
}
