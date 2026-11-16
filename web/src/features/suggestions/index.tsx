import type { Feature } from '../../shell/feature';
import { SuggestionsPage } from './SuggestionsPage';

export const suggestionsFeature: Feature = {
  routes: [{ path: '/suggestions', element: <SuggestionsPage />, roles: ['MEMBER'] }],
  nav: [{ to: '/suggestions', label: 'Suggestions', description: 'Propose a line to your Manager and follow the decision.', roles: ['MEMBER'] }],
};
