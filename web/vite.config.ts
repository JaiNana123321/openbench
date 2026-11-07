import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The back end (Spring Boot) listens on 8080. In development the browser talks only to
// Vite, which forwards /api, so the client and the API share one origin and its cookies.
const api = { '/api': 'http://localhost:8080' };

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: api },
  preview: { port: 4173, proxy: api },
  build: { outDir: 'dist', emptyOutDir: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // `npm run coverage` writes coverage/ (index.html, lcov.info, coverage-summary.json) and fails below
    // the thresholds, set a few points under the measured baseline (TESTING.md).
    coverage: {
      provider: 'v8',
      reportsDirectory: 'coverage',
      reporter: ['text-summary', 'html', 'lcov', 'json-summary'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        // The test helpers themselves.
        'src/test/**',
        // Only mounts <App> into the page's #root; the tests render App through renderApp instead.
        'src/main.tsx',
      ],
      // Baseline on 2026-11-07: statements 95.0%, branches 83.1%, functions 96.8%, lines 97.4%.
      thresholds: { statements: 90, branches: 78, functions: 92, lines: 92 },
    },
  },
});
