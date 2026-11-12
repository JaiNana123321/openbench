import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type {
  AdminChange,
  Id,
  MemberSummary,
  NewMember,
  Page,
  ResourceType,
  ResourceTypeDefinition,
  Skill,
  StoredRole,
  TeamOverview,
} from '../../api/types';

// The Administrator's Resource Type calls (design Section 7, "Inventory"). The keys are the
// Inventory screens' keys, so a change here refreshes those screens too.

const typeKeys = {
  all: ['inventory'] as const,
  list: ['inventory', 'types', { includeRetired: true }] as const,
  one: (id: Id) => ['inventory', 'types', id] as const,
};

/** Every Resource Type, retired ones included. */
export function useAllResourceTypes() {
  return useQuery({
    queryKey: typeKeys.list,
    queryFn: () => api.get<ResourceType[]>('/api/inventory/types', { includeRetired: true }),
  });
}

export function useResourceType(id: Id) {
  return useQuery({
    queryKey: typeKeys.one(id),
    queryFn: () => api.get<ResourceType>(`/api/inventory/types/${id}`),
  });
}

function useInvalidateInventory() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: typeKeys.all });
}

export function useDefineType() {
  const invalidate = useInvalidateInventory();
  return useMutation({
    mutationFn: (definition: ResourceTypeDefinition) => api.post<ResourceType>('/api/inventory/types', definition),
    onSuccess: invalidate,
  });
}

export function useChangeType(id: Id) {
  const invalidate = useInvalidateInventory();
  return useMutation({
    mutationFn: (definition: ResourceTypeDefinition) => api.put<ResourceType>(`/api/inventory/types/${id}`, definition),
    onSuccess: invalidate,
  });
}

export function useRetireType(id: Id) {
  const invalidate = useInvalidateInventory();
  return useMutation({
    mutationFn: () => api.post<ResourceType>(`/api/inventory/types/${id}/retire`),
    onSuccess: invalidate,
  });
}

// The Administrator's skill list (design Section 7, "Members and administration"). The keys are
// the profile screens' keys, so a change here refreshes the skills Members are offered.

const skillKeys = {
  all: ['skills'] as const,
  list: ['skills', { includeRetired: true }] as const,
};

/** Every skill, retired ones included, by name. */
export function useAllSkills() {
  return useQuery({
    queryKey: skillKeys.list,
    queryFn: () => api.get<Skill[]>('/api/skills', { includeRetired: true }),
  });
}

function useInvalidateSkills() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: skillKeys.all });
}

export function useAddSkill() {
  const invalidate = useInvalidateSkills();
  return useMutation({
    mutationFn: (name: string) => api.post<Skill>('/api/admin/skills', { name }),
    onSuccess: invalidate,
  });
}

export function useRetireSkill() {
  const invalidate = useInvalidateSkills();
  return useMutation({
    mutationFn: (id: Id) => api.post<Skill>(`/api/admin/skills/${id}/retire`),
    onSuccess: invalidate,
  });
}

// The Administrator's Members, Teams, and roles (design Section 7, "Members and administration").
// A change invalidates ['admin'] (the Teams overview and the change record) and ['teams'] (the
// active Teams and every roster, the profile screens' included).

export const ROSTER_PAGE_SIZE = 50;
export const CHANGES_PAGE_SIZE = 50;

const adminKeys = {
  all: ['admin'] as const,
  teams: ['admin', 'teams'] as const,
  changes: (page: number) => ['admin', 'changes', page] as const,
  roster: (teamId: Id, page: number) => ['teams', teamId, 'members', 'admin', page] as const,
};

/** Every Team, retired ones included, by name, with its Manager and active Members. */
export function useTeamOverview() {
  return useQuery({
    queryKey: adminKeys.teams,
    queryFn: () => api.get<TeamOverview[]>('/api/admin/teams'),
  });
}

/** A Team's Members, active or not, by name. */
export function useAdminRoster(teamId: Id | undefined, page: number) {
  return useQuery({
    queryKey: adminKeys.roster(teamId ?? 0, page),
    queryFn: () =>
      api.get<Page<MemberSummary>>(`/api/teams/${teamId}/members`, { page, size: ROSTER_PAGE_SIZE }),
    enabled: teamId !== undefined,
  });
}

/** The record of the Administrators' changes, newest first. */
export function useAdminChanges(page: number) {
  return useQuery({
    queryKey: adminKeys.changes(page),
    queryFn: () => api.get<Page<AdminChange>>('/api/admin/changes', { page, size: CHANGES_PAGE_SIZE }),
  });
}

function useInvalidateAdministration() {
  const queryClient = useQueryClient();
  return async () => {
    await queryClient.invalidateQueries({ queryKey: adminKeys.all });
    await queryClient.invalidateQueries({ queryKey: ['teams'] });
  };
}

export function useCreateMember() {
  const invalidate = useInvalidateAdministration();
  return useMutation({
    mutationFn: (member: NewMember) => api.post<MemberSummary>('/api/admin/members', member),
    onSuccess: invalidate,
  });
}

export function useDeactivateMember() {
  const invalidate = useInvalidateAdministration();
  return useMutation({
    mutationFn: (id: Id) => api.post<MemberSummary>(`/api/admin/members/${id}/deactivate`),
    onSuccess: invalidate,
  });
}

export function useMoveMember() {
  const invalidate = useInvalidateAdministration();
  return useMutation({
    mutationFn: ({ id, teamId }: { id: Id; teamId: Id }) =>
      api.post<MemberSummary>(`/api/admin/members/${id}/move`, { teamId }),
    onSuccess: invalidate,
  });
}

export function useGrantRole() {
  const invalidate = useInvalidateAdministration();
  return useMutation({
    mutationFn: ({ memberId, role }: { memberId: Id; role: StoredRole }) =>
      api.post<MemberSummary>('/api/admin/roles', { memberId, role }),
    onSuccess: invalidate,
  });
}

export function useRevokeRole() {
  const invalidate = useInvalidateAdministration();
  return useMutation({
    mutationFn: ({ memberId, role }: { memberId: Id; role: StoredRole }) =>
      api.delete<MemberSummary>(`/api/admin/roles/${memberId}/${role}`),
    onSuccess: invalidate,
  });
}

export function useCreateTeam() {
  const invalidate = useInvalidateAdministration();
  return useMutation({
    mutationFn: (name: string) => api.post<TeamOverview>('/api/admin/teams', { name }),
    onSuccess: invalidate,
  });
}

export function useRenameTeam() {
  const invalidate = useInvalidateAdministration();
  return useMutation({
    mutationFn: ({ id, name }: { id: Id; name: string }) => api.put<TeamOverview>(`/api/admin/teams/${id}`, { name }),
    onSuccess: invalidate,
  });
}

export function useRetireTeam() {
  const invalidate = useInvalidateAdministration();
  return useMutation({
    mutationFn: (id: Id) => api.post<TeamOverview>(`/api/admin/teams/${id}/retire`),
    onSuccess: invalidate,
  });
}
