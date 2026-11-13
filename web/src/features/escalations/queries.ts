import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { ApprovalView, Decision, EscalatedRequest, Id } from '../../api/types';

// The escalations API (design Section 7, "Approvals and escalations"). The list's key is
// ['escalations']; a decision refreshes it, the approvals, and the Requests, whose lines it changes.

export const escalationKeys = {
  all: ['escalations'] as const,
};

/** The escalated Requests, the longest lapsed first. */
export function useEscalations() {
  return useQuery({
    queryKey: escalationKeys.all,
    queryFn: () => api.get<EscalatedRequest[]>('/api/escalations'),
  });
}

/** POST /api/approvals/{id}/decision as the Capacity Planner, for the silent Manager. */
export function useDecideEscalated() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, decision, reason }: { id: Id; decision: Decision; reason: string }) =>
      api.post<ApprovalView>(`/api/approvals/${id}/decision`, { decision, reason }),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: escalationKeys.all }),
        queryClient.invalidateQueries({ queryKey: ['approvals'] }),
        queryClient.invalidateQueries({ queryKey: ['requests'] }),
      ]),
  });
}
