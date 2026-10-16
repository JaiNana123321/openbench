import type { ReactNode } from 'react';
import type { Role } from '../api/types';

/**
 * What a feature folder (src/features/<feature>/index.tsx) exports so the shell can show it.
 * src/routes.tsx lists every feature; adding a screen is a new folder plus one line there.
 */
export interface Feature {
  /** The feature's pages. */
  routes: FeatureRoute[];
  /** Its entries in the main menu, in menu order; may be empty. */
  nav: NavItem[];
}

export interface FeatureRoute {
  /** An absolute path, such as "/requests/:id". */
  path: string;
  element: ReactNode;
  /** Members holding any of these roles may open the page; others see "Not allowed". MEMBER is everyone. */
  roles: readonly Role[];
}

export interface NavItem {
  to: string;
  label: string;
  /** One line for the home page, which links to every item the Member's roles allow. */
  description: string;
  /** The menu shows the item only to Members holding any of these roles. MEMBER is everyone. */
  roles: readonly Role[];
}
