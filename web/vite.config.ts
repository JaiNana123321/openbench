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
  },
});
