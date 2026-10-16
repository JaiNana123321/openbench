import { createContext, useContext } from 'react';
import type { Me } from '../api/types';

/** Provided by RequireAuth around every signed-in page. */
export const CurrentMemberContext = createContext<Me | null>(null);

/** The signed-in Member, on any page behind RequireAuth. */
export function useCurrentMember(): Me {
  const me = useContext(CurrentMemberContext);
  if (me === null) throw new Error('useCurrentMember is only available inside RequireAuth.');
  return me;
}
