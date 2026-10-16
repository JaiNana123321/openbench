import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { isApiError } from './api/client';
import { meQueryKey } from './auth/session';

/**
 * The cache of server state, the client's only store (design Section 4, Web client).
 * Reads retry up to three times when the server is unreachable or failing (status 0 or 5xx);
 * writes never retry. A 401 from any call means the session ended: the Member is marked signed
 * out, and RequireAuth sends them to /login.
 */
export function createQueryClient(): QueryClient {
  const queryClient: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError: signOutOn401 }),
    mutationCache: new MutationCache({ onError: signOutOn401 }),
    defaultOptions: {
      queries: { retry: retryReads },
      mutations: { retry: false },
    },
  });

  function signOutOn401(error: unknown) {
    if (isApiError(error) && error.status === 401) queryClient.setQueryData(meQueryKey, null);
  }

  return queryClient;
}

function retryReads(failureCount: number, error: unknown): boolean {
  const transient = isApiError(error) && (error.status === 0 || error.status >= 500);
  return transient && failureCount < 3;
}
