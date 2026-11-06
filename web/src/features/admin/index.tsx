import type { Feature } from '../../shell/feature';
import { ChangeTypePage, DefineTypePage, ResourceTypesPage } from './ResourceTypesPage';
import { SkillsPage } from './SkillsPage';

/** The Administrator's screens: Resource Types and the skill list; Members and Teams come later. */
export const adminFeature: Feature = {
  routes: [
    { path: '/admin/types', element: <ResourceTypesPage />, roles: ['ADMIN'] },
    { path: '/admin/types/new', element: <DefineTypePage />, roles: ['ADMIN'] },
    { path: '/admin/types/:id', element: <ChangeTypePage />, roles: ['ADMIN'] },
    { path: '/admin/skills', element: <SkillsPage />, roles: ['ADMIN'] },
  ],
  nav: [
    {
      to: '/admin/types',
      label: 'Resource Types',
      description: 'Define, change, and retire the kinds of Resources.',
      roles: ['ADMIN'],
    },
    {
      to: '/admin/skills',
      label: 'Skill list',
      description: 'Add and retire the skills Members choose from.',
      roles: ['ADMIN'],
    },
  ],
};
