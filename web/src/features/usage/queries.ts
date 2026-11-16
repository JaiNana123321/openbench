import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Id, MeteredUsage, Period } from '../../api/types';

// Query keys start with the API group's noun: ['usage', periodId]. Recording usage also changes the
// dashboard's comparison, so it invalidates ['dashboard', 'usage'] too.

/** Every Period, by start date: GET /api/settings/periods. */
export function usePeriods() {
  return useQuery({
    queryKey: ['settings', 'periods'],
    queryFn: () => api.get<Period[]>('/api/settings/periods'),
  });
}

/** The Period's Allocated Metered allocations of the caller's types. */
export function useUsage(periodId: Id | undefined) {
  return useQuery({
    queryKey: ['usage', periodId],
    queryFn: () => api.get<MeteredUsage[]>('/api/usage', { periodId }),
    enabled: periodId !== undefined,
  });
}

/** Records the units one allocation used, replacing any value recorded before. */
export function useRecordUsage(id: Id) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (usedUnits: number) => api.put<MeteredUsage>(`/api/usage/${id}`, { usedUnits }),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: ['usage'] }),
        client.invalidateQueries({ queryKey: ['dashboard', 'usage'] }),
      ]),
  });
}
