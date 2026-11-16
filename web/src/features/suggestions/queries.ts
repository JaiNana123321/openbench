import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCurrentMember } from '../../auth/currentMember';
import { api } from '../../api/client';
import type { DraftView, Id, NewLine, Page, RequestSummary, ResourceType, Skill, Suggestion } from '../../api/types';

export function useSuggestions(team: boolean, page: number) {
  return useQuery({
    queryKey: ['suggestions', team ? 'team' : 'mine', page],
    queryFn: () => api.get<Page<Suggestion>>(`/api/suggestions/${team ? 'team' : 'mine'}`, { page, size: 50 }),
  });
}

export function useSuggestionTypes() {
  return useQuery({
    queryKey: ['inventory', 'types', { includeRetired: false }],
    queryFn: () => api.get<ResourceType[]>('/api/inventory/types', { includeRetired: false }),
  });
}

export function useSuggestionSkills(enabled: boolean) {
  return useQuery({
    queryKey: ['skills', { includeRetired: false }],
    queryFn: () => api.get<Skill[]>('/api/skills', { includeRetired: false }),
    enabled,
  });
}

function useRefreshSuggestions() {
  const client = useQueryClient();
  return () => Promise.all([
    client.invalidateQueries({ queryKey: ['suggestions'] }),
    client.invalidateQueries({ queryKey: ['requests'] }),
    client.invalidateQueries({ queryKey: ['notifications'] }),
  ]);
}

export function useCreateSuggestion() {
  const refresh = useRefreshSuggestions();
  return useMutation({
    mutationFn: (body: { lines: NewLine[]; note: string }) => api.post<Suggestion>('/api/suggestions', body),
    onSuccess: refresh,
  });
}

export function useDecideSuggestion(id: Id) {
  const refresh = useRefreshSuggestions();
  return useMutation({
    mutationFn: (decision: { action: 'accept'; requestId?: Id; lineId?: Id } | { action: 'decline'; reason: string }) => {
      const { action, ...body } = decision;
      return api.post<Suggestion>(`/api/suggestions/${id}/${action}`, body);
    },
    onSuccess: refresh,
  });
}

export function useSuggestionDrafts(page: number) {
  const me = useCurrentMember();
  return useQuery({
    queryKey: ['requests', 'suggestion-drafts', me.team.id, page],
    queryFn: () => api.get<Page<RequestSummary>>('/api/requests', { team: me.team.id, state: 'DRAFT', page, size: 50 }),
  });
}

export function useSuggestionDraft(id: Id | undefined) {
  return useQuery({
    queryKey: ['requests', id, 'draft'],
    queryFn: () => api.get<DraftView>(`/api/requests/${id}/draft`),
    enabled: id !== undefined,
  });
}

