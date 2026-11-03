/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In docker-compose nginx serves the build and forwards /api to the Gateway (nginx.conf).
// `npm run dev` does the same against a Gateway on the host.
export default defineConfig({
  plugins: [react()],
  build: {
    // Never inline assets as data: URIs: the CSP (nginx/security-headers.conf) allows fonts from 'self' only.
    assetsInlineLimit: 0,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.API_TARGET ?? 'http://localhost:8080',
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    restoreMocks: true,
    coverage: {
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/main.tsx', 'src/test/**', 'src/**/*.test.{ts,tsx}', 'src/vite-env.d.ts'],
      thresholds: { lines: 90 },
    },
  },
});
