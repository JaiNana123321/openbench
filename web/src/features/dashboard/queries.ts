import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Id, Period, PersonDays, TeamCredits, TypeIdle, TypeUtilization, TypeWaiting } from '../../api/types';

// Query keys start with the API group's noun: ['dashboard', 'utilization', periodId], and so on.
// Nothing is cached across visits beyond TanStack Query's default: the figures are read at request
// time on the server (SRS Dashboard.Fresh).

/** Every Period, by start date: GET /api/settings/periods. */
export function usePeriods() {
  return useQuery({
    queryKey: ['settings', 'periods'],
    queryFn: () => api.get<Period[]>('/api/settings/periods'),
  });
}

/** Utilization of each type in the caller's scope over the Period. */
export function useUtilization(periodId: Id | undefined) {
  return useQuery({
    queryKey: ['dashboard', 'utilization', periodId],
    queryFn: () => api.get<TypeUtilization[]>('/api/dashboard/utilization', { periodId }),
    enabled: periodId !== undefined,
  });
}

/** Idle hours of each type in scope over the coming seven days. */
export function useIdle() {
  return useQuery({
    queryKey: ['dashboard', 'idle', 7],
    queryFn: () => api.get<TypeIdle[]>('/api/dashboard/idle', { days: 7 }),
  });
}

/** The lines waiting on each type in scope now. */
export function useWaiting() {
  return useQuery({
    queryKey: ['dashboard', 'waiting'],
    queryFn: () => api.get<TypeWaiting[]>('/api/dashboard/waiting'),
  });
}

/** Each Team's credits in scope for the Period; only Managers and the Capacity Planner ask. */
export function useTeamCredits(periodId: Id | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['dashboard', 'credits', periodId],
    queryFn: () => api.get<TeamCredits[]>('/api/dashboard/credits', { periodId }),
    enabled: enabled && periodId !== undefined,
  });
}

/** Each Team's person-days in scope for the Period; only Managers and the Capacity Planner ask. */
export function usePersonDays(periodId: Id | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['dashboard', 'person-days', periodId],
    queryFn: () => api.get<PersonDays[]>('/api/dashboard/person-days', { periodId }),
    enabled: enabled && periodId !== undefined,
  });
}
