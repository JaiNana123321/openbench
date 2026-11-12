import type { Feature } from '../../shell/feature';
import { ChangesPage } from './ChangesPage';
import { MembersPage } from './MembersPage';
import { ChangeTypePage, DefineTypePage, ResourceTypesPage } from './ResourceTypesPage';
import { SkillsPage } from './SkillsPage';
import { TeamsPage } from './TeamsPage';

/** The Administrator's screens: Resource Types, the skill list, Members, Teams, and the change record. */
export const adminFeature: Feature = {
  routes: [
    { path: '/admin/types', element: <ResourceTypesPage />, roles: ['ADMIN'] },
    { path: '/admin/types/new', element: <DefineTypePage />, roles: ['ADMIN'] },
    { path: '/admin/types/:id', element: <ChangeTypePage />, roles: ['ADMIN'] },
    { path: '/admin/skills', element: <SkillsPage />, roles: ['ADMIN'] },
    { path: '/admin/members', element: <MembersPage />, roles: ['ADMIN'] },
    { path: '/admin/teams', element: <TeamsPage />, roles: ['ADMIN'] },
    { path: '/admin/changes', element: <ChangesPage />, roles: ['ADMIN'] },
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
    {
      to: '/admin/members',
      label: 'Members',
      description: 'Add and deactivate Members, move them between Teams, and grant or revoke roles.',
      roles: ['ADMIN'],
    },
    {
      to: '/admin/teams',
      label: 'Teams',
      description: 'Add, rename, and retire Teams.',
      roles: ['ADMIN'],
    },
    {
      to: '/admin/changes',
      label: 'Change record',
      description: 'Every change to Members, Teams, and roles, with who made it and when.',
      roles: ['ADMIN'],
    },
  ],
};
