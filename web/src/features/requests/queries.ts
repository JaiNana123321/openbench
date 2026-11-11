import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type {
  DraftView,
  Id,
  LineEdit,
  MemberSummary,
  NewLine,
  Page,
  RefundQuote,
  ReleaseBody,
  ReleaseOutcome,
  RequestDetail,
  RequestLine,
  RequestRecord,
  RequestSummary,
  Resource,
  ResourceType,
  Shortlist,
  Skill,
} from '../../api/types';

// The Requests API (design Section 7, "Requests"). Keys start with 'requests': ['requests', 'list', ...]
// for the list, ['requests', id] for the detail, and ['requests', id, 'draft'] for a Draft view, so a
// line edit refreshes all three. Submit and withdraw also refresh ['ledger'], the Account and Statement.
// The types and Resources for the add-line form use the Inventory keys and share its cache.

export const requestKeys = {
  all: ['requests'] as const,
  list: (page: number) => ['requests', 'list', { page }] as const,
  request: (id: Id) => ['requests', id] as const,
  draft: (id: Id) => ['requests', id, 'draft'] as const,
};

export const REQUEST_PAGE_SIZE = 50;

/** One page of the Team's Requests, newest first (every Team's for a Capacity Planner). */
export function useRequests(page: number) {
  return useQuery({
    queryKey: requestKeys.list(page),
    queryFn: () => api.get<Page<RequestSummary>>('/api/requests', { page, size: REQUEST_PAGE_SIZE }),
  });
}

/** A Request with its lines, Quotes, sums, Available Balance, and shortfall, as the server computed them. */
export function useDraft(id: Id) {
  return useQuery({
    queryKey: requestKeys.draft(id),
    queryFn: () => api.get<DraftView>(`/api/requests/${id}/draft`),
  });
}

/** The unretired Resource Types; the add-line form offers the Exclusive and Metered ones. */
export function useLineTypes() {
  return useQuery({
    queryKey: ['inventory', 'types', { includeRetired: false }],
    queryFn: () => api.get<ResourceType[]>('/api/inventory/types', { includeRetired: false }),
  });
}

/** The unretired Resources of an Exclusive type, for naming one; waits while no type is chosen. */
export function useResourcesOfType(typeId: Id | undefined) {
  return useQuery({
    queryKey: ['inventory', 'resources', { type: typeId, page: 0, size: 200 }],
    queryFn: () => api.get<Page<Resource>>('/api/inventory/resources', { type: typeId, page: 0, size: 200 }),
    enabled: typeId !== undefined,
  });
}

function useInvalidateRequests() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: requestKeys.all });
}

export function useCreateRequest() {
  const invalidate = useInvalidateRequests();
  return useMutation({
    mutationFn: () => api.post<RequestRecord>('/api/requests'),
    onSuccess: invalidate,
  });
}

export function useAddLine(requestId: Id) {
  const invalidate = useInvalidateRequests();
  return useMutation({
    mutationFn: (line: NewLine) => api.post<RequestLine>(`/api/requests/${requestId}/lines`, line),
    onSuccess: invalidate,
  });
}

export function useEditLine(requestId: Id) {
  const invalidate = useInvalidateRequests();
  return useMutation({
    mutationFn: ({ lineId, edit }: { lineId: Id; edit: LineEdit }) =>
      api.put<RequestLine>(`/api/requests/${requestId}/lines/${lineId}`, edit),
    onSuccess: invalidate,
  });
}

export function useRemoveLine(requestId: Id) {
  const invalidate = useInvalidateRequests();
  return useMutation({
    mutationFn: (lineId: Id) => api.delete(`/api/requests/${requestId}/lines/${lineId}`),
    onSuccess: invalidate,
  });
}

/** Moves `quantity` instances or units of a line to a new Optional line. */
export function useSplitLine(requestId: Id) {
  const invalidate = useInvalidateRequests();
  return useMutation({
    mutationFn: ({ lineId, quantity }: { lineId: Id; quantity: number }) =>
      api.post<RequestLine>(`/api/requests/${requestId}/lines/${lineId}/split`, { quantity }),
    onSuccess: invalidate,
  });
}

export function useSetRequired(requestId: Id) {
  const invalidate = useInvalidateRequests();
  return useMutation({
    mutationFn: ({ lineId, required }: { lineId: Id; required: boolean }) =>
      api.put<RequestLine>(`/api/requests/${requestId}/lines/${lineId}/required`, { required }),
    onSuccess: invalidate,
  });
}

/** A Request's state, its lines with their states, Resources, reasons, and waitlist positions, and its history. */
export function useRequestDetail(id: Id) {
  return useQuery({
    queryKey: requestKeys.request(id),
    queryFn: () => api.get<RequestDetail>(`/api/requests/${id}`),
  });
}

/** A submit or withdraw changes the Request, the list, and the Account and Statement. */
function useInvalidateTransition() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: requestKeys.all }),
      queryClient.invalidateQueries({ queryKey: ['ledger'] }),
    ]);
}

/** Submits a Draft; it comes back Allocated or Waiting. A refusal such as INSUFFICIENT_BALANCE is the mutation's error. */
export function useSubmitRequest(requestId: Id) {
  const invalidate = useInvalidateTransition();
  return useMutation({
    mutationFn: () => api.post<RequestRecord>(`/api/requests/${requestId}/submit`),
    onSuccess: invalidate,
  });
}

export function useWithdrawRequest(requestId: Id) {
  const invalidate = useInvalidateTransition();
  return useMutation({
    mutationFn: () => api.post<RequestRecord>(`/api/requests/${requestId}/withdraw`),
    onSuccess: invalidate,
  });
}

/**
 * The Refund a Release of this part would post now, as the server computes it; waits while the
 * part is incomplete. The occurrences go as one comma-separated parameter.
 */
export function useReleasePreview(requestId: Id, lineId: Id, body: ReleaseBody | undefined) {
  return useQuery({
    queryKey: [...requestKeys.request(requestId), 'release-preview', lineId, body] as const,
    queryFn: () =>
      api.get<RefundQuote>(`/api/requests/${requestId}/lines/${lineId}/release-preview`, {
        kind: body?.kind,
        newEnd: body?.newEnd,
        newUnits: body?.newUnits,
        occurrences: body?.occurrences?.join(','),
      }),
    enabled: body !== undefined,
  });
}

// ---------------------------------------------------------------- Person lines

/** The skill list, offered skills only, under the same key as the profiles feature. */
export function useOfferedSkills() {
  return useQuery({
    queryKey: ['skills', { includeRetired: false }],
    queryFn: () => api.get<Skill[]>('/api/skills', { includeRetired: false }),
  });
}

/** The active Members of other Teams, whom a Person line may name. */
export function useLendableMembers() {
  return useQuery({
    queryKey: ['members', 'lendable'],
    queryFn: () => api.get<MemberSummary[]>('/api/members/lendable'),
  });
}

/** A Person line's Shortlist, computed by the server on every call; waits while `enabled` is false. */
export function useShortlist(requestId: Id, lineId: Id, enabled: boolean) {
  return useQuery({
    queryKey: [...requestKeys.request(requestId), 'shortlist', lineId] as const,
    queryFn: () => api.get<Shortlist>(`/api/requests/${requestId}/lines/${lineId}/shortlist`),
    enabled,
  });
}

/** Selects Candidates for a Person line; a Waiting Request may go back to Pending and hold again. */
export function useSelectCandidates(requestId: Id) {
  const invalidate = useInvalidateTransition();
  return useMutation({
    mutationFn: ({ lineId, members }: { lineId: Id; members: Id[] }) =>
      api.post<RequestDetail>(`/api/requests/${requestId}/lines/${lineId}/candidates`, { members }),
    onSuccess: invalidate,
  });
}

/** Releases part of a line; the Refund changes the Account and Statement, and a fill may change other Requests. */
export function useReleaseLine(requestId: Id) {
  const invalidate = useInvalidateTransition();
  return useMutation({
    mutationFn: ({ lineId, body }: { lineId: Id; body: ReleaseBody }) =>
      api.post<ReleaseOutcome>(`/api/requests/${requestId}/lines/${lineId}/release`, body),
    onSuccess: invalidate,
  });
}
