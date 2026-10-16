import { createContext, useContext } from 'react';
import type { NavItem } from './feature';

/** Provided by AppShell: the menu items the signed-in Member's roles allow. */
export const NavItemsContext = createContext<readonly NavItem[]>([]);

/** The menu items the signed-in Member's roles allow, in menu order. */
export function useNavItems(): readonly NavItem[] {
  return useContext(NavItemsContext);
}
