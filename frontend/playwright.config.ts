import { defineConfig } from '@playwright/test';

// End-to-end against the whole stack: `docker compose --profile apps up -d --build`, then `npm run e2e`.
// The flows move real money between the demo accounts, so they run one at a time.
export default defineConfig({
  testDir: 'e2e',
  workers: 1,
  timeout: 90_000,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    locale: 'es-CO',
    trace: 'retain-on-failure',
  },
});
