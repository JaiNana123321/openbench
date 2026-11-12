import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type {
  Account,
  Credits,
  Id,
  Page,
  Period,
  PlannerSettings,
  ResourceType,
  SettingChangeEntry,
  TeamRef,
} from '../../api/types';

// The Capacity Planner's settings (design Section 7, "Settings"). Every key starts with
// 'settings', so a change refreshes the snapshot, the planned Grants, the Periods, and the history.

export const settingsKeys = {
  all: ['settings'] as const,
  current: ['settings', 'current'] as const,
  periods: ['settings', 'periods'] as const,
  grants: (periodId: Id) => ['settings', 'grants', periodId] as const,
  changes: (page: number) => ['settings', 'changes', page] as const,
};

export const CHANGES_PAGE_SIZE = 20;

/** Every setting: GET /api/settings. */
export function useSettings() {
  return useQuery({
    queryKey: settingsKeys.current,
    queryFn: () => api.get<PlannerSettings>('/api/settings'),
  });
}

/** Every Period, by start date: GET /api/settings/periods. */
export function usePeriods() {
  return useQuery({
    queryKey: settingsKeys.periods,
    queryFn: () => api.get<Period[]>('/api/settings/periods'),
  });
}

/** Each Team's planned Grant for a Period, keyed by Team id; waits while no Period is chosen. */
export function usePlannedGrants(periodId: Id | undefined) {
  return useQuery({
    queryKey: settingsKeys.grants(periodId ?? 0),
    queryFn: () => api.get<Record<string, Credits>>(`/api/settings/grants/${periodId}`),
    enabled: periodId !== undefined,
  });
}

/** One page of the change log, newest first. */
export function useSettingChanges(page: number) {
  return useQuery({
    queryKey: settingsKeys.changes(page),
    queryFn: () =>
      api.get<Page<SettingChangeEntry>>('/api/settings/changes', { page, size: CHANGES_PAGE_SIZE }),
  });
}

/** The active Teams: GET /api/teams. */
export function useTeams() {
  return useQuery({
    queryKey: ['teams'],
    queryFn: () => api.get<TeamRef[]>('/api/teams'),
  });
}

/** The unretired Resource Types, with the Inventory screens' key. */
export function useResourceTypes() {
  return useQuery({
    queryKey: ['inventory', 'types', { includeRetired: false }],
    queryFn: () => api.get<ResourceType[]>('/api/inventory/types', { includeRetired: false }),
  });
}

/** Changes one setting: PUT /api/settings/{key} with `{"value": ...}`. */
export function useUpdateSetting() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value }: { key: string; value: unknown }) =>
      api.put<PlannerSettings>(`/api/settings/${key}`, { value }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: settingsKeys.all }),
  });
}

/** A mid-Period Grant with a reason: POST /api/ledger/grants. */
export function useMidPeriodGrant() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (grant: { teamId: Id; amount: number; reason: string }) =>
      api.post<Account>('/api/ledger/grants', grant),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ledger'] }),
  });
}
