import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { ApprovalView, Decision, Id } from '../../api/types';

// The approvals API (design Section 7, "Approvals and escalations"). The list's key is ['approvals'];
// a decision refreshes it and the Requests, whose lines it changes.

export const approvalKeys = {
  all: ['approvals'] as const,
};

/** The Outstanding approvals addressed to the signed-in Manager, oldest first. */
export function useApprovals() {
  return useQuery({
    queryKey: approvalKeys.all,
    queryFn: () => api.get<ApprovalView[]>('/api/approvals'),
  });
}

/** POST /api/approvals/{id}/decision: approve or refuse, with a reason. */
export function useDecide() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, decision, reason }: { id: Id; decision: Decision; reason: string }) =>
      api.post<ApprovalView>(`/api/approvals/${id}/decision`, { decision, reason }),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: approvalKeys.all }),
        queryClient.invalidateQueries({ queryKey: ['requests'] }),
      ]),
  });
}
