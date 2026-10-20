import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type {
  DraftView,
  Id,
  LineEdit,
  NewLine,
  Page,
  RequestLine,
  RequestRecord,
  RequestSummary,
  Resource,
  ResourceType,
} from '../../api/types';

// The Requests API (design Section 7, "Requests"). Keys start with 'requests': ['requests', 'list', ...]
// for the list and ['requests', id, 'draft'] for a Draft view, so a line edit refreshes both. The
// types and Resources for the add-line form use the Inventory keys and share its cache.

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
