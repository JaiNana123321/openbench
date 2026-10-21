import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Id, Period, RequestLine, Statement, TeamRef } from '../../api/types';

// Query keys start with the API group's noun: ['settings', 'periods'], ['teams'],
// ['ledger', 'statements', teamId, periodId].

/** Every Period, by start date: GET /api/settings/periods. */
export function usePeriods() {
  return useQuery({
    queryKey: ['settings', 'periods'],
    queryFn: () => api.get<Period[]>('/api/settings/periods'),
  });
}

/** The active Teams, for a Capacity Planner's Team selector: GET /api/teams. */
export function useTeams(enabled: boolean) {
  return useQuery({
    queryKey: ['teams'],
    queryFn: () => api.get<TeamRef[]>('/api/teams'),
    enabled,
  });
}

/**
 * The Request Lines behind a Statement's Transactions, so each Charge links to its Request:
 * GET /api/requests/lines. Lines the caller may not see are left out.
 */
export function useChargedLines(ids: readonly Id[]) {
  const sorted = [...new Set(ids)].sort((a, b) => a - b);
  return useQuery({
    queryKey: ['requests', 'lines', sorted],
    queryFn: () => api.get<RequestLine[]>('/api/requests/lines', { ids: sorted.join(',') }),
    enabled: sorted.length > 0,
  });
}

/** A Team's Statement for a Period, once a Period is chosen. */
export function useStatement(teamId: Id, periodId: Id | undefined) {
  return useQuery({
    queryKey: ['ledger', 'statements', teamId, periodId],
    queryFn: () => api.get<Statement>(`/api/ledger/statements/${teamId}/${periodId}`),
    enabled: periodId !== undefined,
  });
}
