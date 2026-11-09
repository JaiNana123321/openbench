import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Id, MemberNotification } from '../../api/types';
import { notificationKeys } from '../../shell/unreadNotifications';

// The list itself is the header's query (useUnreadNotifications in src/shell), key ['notifications'],
// so marking a notice read here refreshes the header's count too.

/** POST /api/notifications/{id}/read: the notice leaves the unread list. */
export function useMarkRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Id) => api.post<MemberNotification>(`/api/notifications/${id}/read`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: notificationKeys.unread }),
  });
}
