import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { RouterProvider, type DataRouter } from 'react-router';

/** The application: the query cache around the router. main.tsx and the tests both render this. */
export function App({ router, queryClient }: { router: DataRouter; queryClient: QueryClient }) {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
