import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { CurrentMemberContext } from '../auth/currentMember';
import type { LoginState } from '../auth/LoginPage';
import { useMe } from '../auth/session';
import { Loading } from '../ui';
import { ErrorPage } from './ErrorPage';

/**
 * The route guard: renders its children for a signed-in Member and sends anyone else to /login.
 * A 401 from any later call marks the Member signed out, so it also lands here and redirects.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const me = useMe();
  const { pathname, search, hash } = useLocation();

  if (me.isPending) {
    return (
      <main className="app-main">
        <Loading />
      </main>
    );
  }
  if (me.isError) {
    return (
      <main className="app-main">
        <ErrorPage error={me.error} />
      </main>
    );
  }
  if (me.data === null) {
    const state: LoginState = { from: { pathname, search, hash } };
    return <Navigate to="/login" replace state={state} />;
  }
  return <CurrentMemberContext value={me.data}>{children}</CurrentMemberContext>;
}
