import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { DayRange, Id, Loan, MemberProfile, MemberSummary, Page, Skill } from '../../api/types';

// Profile calls (design Section 7, "Members and administration"). Keys start with the API group's
// noun: ['members', id, 'profile'], ['members', id, 'loans'], ['teams', id, 'members'],
// ['skills', { includeRetired }].
// `me` stands for the caller's own id in member paths.

/** The largest roster page the server serves; a Team is smaller than this. */
const ROSTER_SIZE = 200;

export const profileKeys = {
  all: ['members'] as const,
  profile: (id: Id | 'me') => ['members', id, 'profile'] as const,
  loans: (id: Id | 'me') => ['members', id, 'loans'] as const,
  roster: (teamId: Id) => ['teams', teamId, 'members'] as const,
  skills: (includeRetired: boolean) => ['skills', { includeRetired }] as const,
};

export function useProfile(id: Id | 'me') {
  return useQuery({
    queryKey: profileKeys.profile(id),
    queryFn: () => api.get<MemberProfile>(`/api/members/${id}/profile`),
  });
}

/** A Member's Loans, by first day: GET /api/members/{id}/loans, served by Allocation. */
export function useLoans(id: Id | 'me') {
  return useQuery({
    queryKey: profileKeys.loans(id),
    queryFn: () => api.get<Loan[]>(`/api/members/${id}/loans`),
  });
}

/** The skill list by name: the offered skills, or every skill with `includeRetired`. */
export function useSkills(includeRetired = false) {
  return useQuery({
    queryKey: profileKeys.skills(includeRetired),
    queryFn: () => api.get<Skill[]>('/api/skills', { includeRetired }),
  });
}

/** A Team's Members, by name. */
export function useRoster(teamId: Id) {
  return useQuery({
    queryKey: profileKeys.roster(teamId),
    queryFn: () => api.get<Page<MemberSummary>>(`/api/teams/${teamId}/members`, { size: ROSTER_SIZE }),
  });
}

function useInvalidateProfiles() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: profileKeys.all });
}

/** Replaces the caller's skills: PUT /api/members/me/skills. */
export function useUpdateSkills() {
  const invalidate = useInvalidateProfiles();
  return useMutation({
    mutationFn: (skills: Id[]) => api.put<MemberProfile>('/api/members/me/skills', { skills }),
    onSuccess: invalidate,
  });
}

/** Replaces a Member's availability; the server merges ranges that overlap or touch. */
export function useUpdateAvailability(id: Id | 'me') {
  const invalidate = useInvalidateProfiles();
  return useMutation({
    mutationFn: (ranges: DayRange[]) => api.put<MemberProfile>(`/api/members/${id}/availability`, { ranges }),
    onSuccess: invalidate,
  });
}
