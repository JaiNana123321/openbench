import type { Me, Role } from '../api/types';

/** The roles in the words of CONTEXT.md, in the order the client lists them. */
export const ROLE_NAMES: Record<Role, string> = {
  MEMBER: 'Member',
  MANAGER: 'Manager',
  RESOURCE_OWNER: 'Resource Owner',
  PLANNER: 'Capacity Planner',
  ADMIN: 'Administrator',
};

/**
 * Whether the Member holds at least one of the roles. The client uses this only to choose what to
 * show; the server checks the role on every call, so hiding something is never the only guard.
 */
export function hasAnyRole(me: Me, roles: readonly Role[]): boolean {
  return roles.some((role) => me.roles.includes(role));
}
