import { defineConfig, devices } from '@playwright/test';

/**
 * Browser layout checks for the built app, at a phone width and a desktop width.
 *
 * jsdom lays nothing out, so a page that scrolls sideways on a phone, or an input small enough
 * for iOS to zoom into, passes every unit test. These run against `vite preview` of the
 * production build with the API mocked in the browser (e2e/fixtures.ts), so they need no stack.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'retain-on-failure',
    colorScheme: 'light',
    locale: 'en-US',
  },
  projects: [
    {
      name: 'mobile',
      use: { ...devices['Pixel 7'], viewport: { width: 390, height: 844 } },
    },
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
  webServer: {
    command: 'npx vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
