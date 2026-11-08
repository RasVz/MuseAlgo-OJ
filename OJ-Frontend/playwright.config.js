import { defineConfig } from '@playwright/test'
import { join } from 'node:path'
const evidence = process.env.OJ_TEST_OUTPUT_DIR

// 低内存机器（如 8GB 且已有 MySQL/Redis 容器）上，GPU 与 zygote 进程容易触发系统回收；
// 设置 OJ_E2E_LOW_MEM=1 可关闭 GPU/zygote/dev-shm 并限制渲染堆，行为不变。
// 注意：不要加 --single-process，单进程模式下关闭页面会连带退出浏览器，导致下个用例复用时失败。
const lowMemoryArgs = process.env.OJ_E2E_LOW_MEM
  ? ['--disable-gpu', '--disable-dev-shm-usage', '--no-zygote', '--js-flags=--max-old-space-size=256']
  : []

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
    screenshot: 'only-on-failure',
    launchOptions: { args: lowMemoryArgs }
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }]
})
