import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Id, ResourceType, ResourceTypeDefinition } from '../../api/types';

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
