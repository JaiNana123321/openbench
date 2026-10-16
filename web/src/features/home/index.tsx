import type { Feature } from '../../shell/feature';
import { HomePage } from './HomePage';

export const homeFeature: Feature = {
  routes: [{ path: '/', element: <HomePage />, roles: ['MEMBER'] }],
  nav: [
    {
      to: '/',
      label: 'Home',
      description: 'Who you are and what your roles let you do.',
      roles: ['MEMBER'],
    },
  ],
};
