import type { Feature } from '../../shell/feature';
import { MyProfilePage, TeamMemberPage, TeamPage } from './ProfilePages';

/** Every Member's own profile, and the Manager's view of their Team. */
export const profilesFeature: Feature = {
  routes: [
    { path: '/profile', element: <MyProfilePage />, roles: ['MEMBER'] },
    { path: '/team', element: <TeamPage />, roles: ['MANAGER'] },
    { path: '/team/members/:id', element: <TeamMemberPage />, roles: ['MANAGER'] },
  ],
  nav: [
    {
      to: '/profile',
      label: 'My profile',
      description: 'Your skills and the days you are available.',
      roles: ['MEMBER'],
    },
    {
      to: '/team',
      label: 'Team',
      description: 'Your Team’s Members, their profiles, and their availability.',
      roles: ['MANAGER'],
    },
  ],
};
