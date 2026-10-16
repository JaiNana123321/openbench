import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { api, isApiError } from '../api/client';
import type { Me } from '../api/types';

// The session: who is signed in, signing in, and signing out (design Section 7, "Authentication").
// The session itself is an HttpOnly cookie the browser keeps; the client only asks who it is.

export const meQueryKey = ['me'] as const;

/** GET /api/me: the signed-in Member, or `null` when signed out (401). It also sets the CSRF cookie. */
export async function fetchMe(): Promise<Me | null> {
  try {
    return await api.get<Me>('/api/me');
  } catch (error) {
    if (isApiError(error) && error.status === 401) return null;
    throw error;
  }
}

/**
 * The signed-in Member, `null` when signed out. Fetched once per sign-in: roles change only at the
 * next sign-in, and any call that answers 401 marks the Member signed out (see queryClient.ts).
 */
export function useMe() {
  return useQuery({ queryKey: meQueryKey, queryFn: fetchMe, staleTime: Infinity });
}

export interface Credentials {
  username: string;
  password: string;
}

/**
 * Signs in: GET /api/me first, so the CSRF cookie exists, then POST the form to /api/login.
 * On success the query cache starts empty, holding only the new Member.
 */
export function useSignIn() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ username, password }: Credentials) => {
      await fetchMe();
      return api.postForm<Me>('/api/login', { username, password });
    },
    onSuccess: (me) => {
      queryClient.removeQueries();
      queryClient.setQueryData(meQueryKey, me);
    },
  });
}

/** Signs out, clears the query cache, and returns to the sign-in page, even if the call fails. */
export function useSignOut() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: () => api.post('/api/logout'),
    onSettled: async () => {
      await navigate('/login', { replace: true });
      queryClient.clear();
    },
  });
}
