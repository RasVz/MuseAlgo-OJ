import { defineConfig } from '@playwright/test'
import { join } from 'node:path'
const evidence = process.env.OJ_TEST_OUTPUT_DIR

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: evidence ? join(evidence, 'browser-results') : 'test-results',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60000,
  reporter: [['list'], ['html', { open: 'never', outputFolder: evidence ? join(evidence, 'browser-report') : 'playwright-report' }]],
  use: {
    baseURL: process.env.OJ_TEST_WEB || 'http://127.0.0.1:15173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }]
})
