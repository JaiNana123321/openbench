import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Id, ResourceType, ResourceTypeDefinition, Skill } from '../../api/types';

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
