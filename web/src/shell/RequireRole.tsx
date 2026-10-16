import type { ReactNode } from 'react';
import type { Role } from '../api/types';
import { useCurrentMember } from '../auth/currentMember';
import { hasAnyRole } from '../auth/roles';
import { NotAllowedPage } from './NotAllowedPage';

/** Shows a page only to Members holding one of the roles. A convenience: the server refuses anyway. */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children: ReactNode }) {
  const me = useCurrentMember();
  return hasAnyRole(me, roles) ? children : <NotAllowedPage />;
}
