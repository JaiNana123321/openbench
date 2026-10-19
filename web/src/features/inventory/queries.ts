import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Id, NewResource, Page, Resource, ResourceEdit, ResourceType } from '../../api/types';

// The Inventory API (design Section 7, "Inventory"). Every key starts with 'inventory', so a write
// refreshes the types (Pool Capacity) and the Resources together.

export const inventoryKeys = {
  all: ['inventory'] as const,
  types: (includeRetired: boolean) => ['inventory', 'types', { includeRetired }] as const,
  type: (id: Id) => ['inventory', 'types', id] as const,
  resources: (filter: ResourceListFilter) => ['inventory', 'resources', filter] as const,
  resource: (id: Id) => ['inventory', 'resources', id] as const,
};

export interface ResourceListFilter {
  type?: Id;
  page: number;
}

export const RESOURCE_PAGE_SIZE = 50;

export function useResourceTypes(includeRetired = false) {
  return useQuery({
    queryKey: inventoryKeys.types(includeRetired),
    queryFn: () => api.get<ResourceType[]>('/api/inventory/types', { includeRetired }),
  });
}

/** One Resource Type; waits while `id` is undefined. */
export function useResourceType(id: Id | undefined) {
  return useQuery({
    queryKey: inventoryKeys.type(id ?? 0),
    queryFn: () => api.get<ResourceType>(`/api/inventory/types/${id}`),
    enabled: id !== undefined,
  });
}

export function useResources(filter: ResourceListFilter) {
  return useQuery({
    queryKey: inventoryKeys.resources(filter),
    queryFn: () =>
      api.get<Page<Resource>>('/api/inventory/resources', {
        type: filter.type,
        page: filter.page,
        size: RESOURCE_PAGE_SIZE,
      }),
  });
}

export function useResource(id: Id) {
  return useQuery({
    queryKey: inventoryKeys.resource(id),
    queryFn: () => api.get<Resource>(`/api/inventory/resources/${id}`),
  });
}

/** Refreshes every Inventory read after a write. */
function useInvalidateInventory() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: inventoryKeys.all });
}

export function useAddResource() {
  const invalidate = useInvalidateInventory();
  return useMutation({
    mutationFn: (resource: NewResource) => api.post<Resource>('/api/inventory/resources', resource),
    onSuccess: invalidate,
  });
}

export function useEditResource(id: Id) {
  const invalidate = useInvalidateInventory();
  return useMutation({
    mutationFn: (edit: ResourceEdit) => api.put<Resource>(`/api/inventory/resources/${id}`, edit),
    onSuccess: invalidate,
  });
}

export function useSetUnits(id: Id) {
  const invalidate = useInvalidateInventory();
  return useMutation({
    mutationFn: (units: number) => api.put<Resource>(`/api/inventory/resources/${id}/units`, { units }),
    onSuccess: invalidate,
  });
}

export function useRetireResource(id: Id) {
  const invalidate = useInvalidateInventory();
  return useMutation({
    mutationFn: () => api.post<Resource>(`/api/inventory/resources/${id}/retire`),
    onSuccess: invalidate,
  });
}
