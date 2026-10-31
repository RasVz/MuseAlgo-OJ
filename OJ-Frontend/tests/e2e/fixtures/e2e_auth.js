// QA-02 合成账号登录/鉴权辅助。
// authLogin 接口按 IP 限流（60s / 10 次），因此每个角色只登录一次并复用 token，
// 页面通过 addInitScript 注入 localStorage（accessToken / refreshToken / user），
// 不做逐条用例的 UI 登录。所有数据均为合成数据，不包含真实姓名/学号。
import { test as base, request } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

export const API_BASE = (process.env.OJ_TEST_API || 'http://127.0.0.1:18020').replace(/\/+$/, '')
export const WEB_BASE = (process.env.OJ_TEST_WEB || 'http://127.0.0.1:15173').replace(/\/+$/, '')
export const PASSWORD = '123456'

// Playwright 默认每个 spec 文件一个独立 worker 进程（即使 workers=1），
// 而登录接口按 IP 限流（10 次/60s），所以 token 缓存必须落到磁盘而不是进程内 Map。
const CACHE_FILE = join(process.env.OJ_TEST_OUTPUT_DIR || tmpdir(), 'qa02-auth-cache.json')

function readCache() {
  try {
    return JSON.parse(readFileSync(CACHE_FILE, 'utf8'))
  } catch {
    return {}
  }
}

function writeCache(cache) {
  try {
    writeFileSync(CACHE_FILE, JSON.stringify(cache), 'utf8')
  } catch {
    // 证据目录不可写时退化为纯内存缓存；不影响测试结论。
  }
}

/** 通过统一登录接口拿到某个合成角色的 token（磁盘级缓存，避免触发 authLogin 限流）。 */
export async function login(username, password = PASSWORD) {
  const cacheKey = username + ':' + password
  const cache = readCache()
  if (cache[cacheKey] && cache[cacheKey].accessToken) return cache[cacheKey]
  const ctx = await request.newContext({ baseURL: API_BASE, timeout: 30000 })
  try {
    const resp = await ctx.post('/auth/login', { data: { username, password, rememberMe: true } })
    const body = await resp.json().catch(() => null)
    if (!body || body.code !== 200 || !body.data || !body.data.accessToken) {
      throw new Error('合成账号登录失败（' + username + '）：HTTP ' + resp.status() + ' / code=' + (body && body.code) + ' / ' + (body && body.message))
    }
    const session = {
      username,
      accessToken: body.data.accessToken,
      refreshToken: body.data.refreshToken,
      user: body.data.user,
    }
    cache[cacheKey] = session
    writeCache(cache)
    return session
  } finally {
    await ctx.dispose()
  }
}

export function storePayload(session) {
  return {
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    user: JSON.stringify(session.user),
  }
}

/** 在 context 层注入登录态：每次导航到真实 origin 时写入 localStorage。 */
export async function injectSession(context, session) {
  await context.addInitScript((values) => {
    try {
      window.localStorage.setItem('accessToken', values.accessToken)
      window.localStorage.setItem('refreshToken', values.refreshToken)
      window.localStorage.setItem('user', values.user)
    } catch {
      // about:blank 等不透明 origin 无法写 localStorage，导航到真实 origin 后会再次执行。
    }
  }, storePayload(session))
}

/** 包装 APIRequestContext，返回 HTTP 状态 + 业务 body（后端统一 HTTP 200 + body.code 的业务错误也能拿到）。 */
export function createApi(ctx) {
  async function call(method, path, options = {}) {
    const headers = Object.assign({}, options.headers || {})
    if (options.session) headers.Authorization = 'Bearer ' + options.session.accessToken
    const opts = { method, headers }
    if (options.data !== undefined) opts.data = options.data
    if (options.params !== undefined) opts.params = options.params
    const resp = await ctx.fetch(path, opts)
    const status = resp.status()
    const contentType = resp.headers()['content-type'] || ''
    let body = null
    let text = ''
    if (contentType.includes('json')) {
      body = await resp.json().catch(() => null)
    } else {
      text = await resp.text().catch(() => '')
    }
    return { status, body, text, contentType, headers: resp.headers() }
  }
  return {
    call,
    get: (path, options) => call('GET', path, options),
    post: (path, options) => call('POST', path, options),
    put: (path, options) => call('PUT', path, options),
    del: (path, options) => call('DELETE', path, options),
  }
}

async function makeRolePage(browser, session, use) {
  const context = await browser.newContext({ baseURL: WEB_BASE, viewport: { width: 1440, height: 900 } })
  await injectSession(context, session)
  const page = await context.newPage()
  try {
    await use(page)
  } finally {
    await context.close()
  }
}

/** 共享 fixtures：角色 session / 角色 page / 直连后端 API 客户端。 */
export const test = base.extend({
  // eslint-disable-next-line no-empty-pattern
  adminSession: async ({}, use) => {
    await use(await login('admin'))
  },
  // eslint-disable-next-line no-empty-pattern
  teacherSession: async ({}, use) => {
    await use(await login('teacher01'))
  },
  // eslint-disable-next-line no-empty-pattern
  otherTeacherSession: async ({}, use) => {
    await use(await login('teacher02'))
  },
  // eslint-disable-next-line no-empty-pattern
  studentSession: async ({}, use) => {
    await use(await login('student07'))
  },
  // eslint-disable-next-line no-empty-pattern
  api: async ({}, use) => {
    const ctx = await request.newContext({ baseURL: API_BASE, timeout: 30000 })
    try {
      await use(createApi(ctx))
    } finally {
      await ctx.dispose()
    }
  },
  adminPage: async ({ browser, adminSession }, use) => {
    await makeRolePage(browser, adminSession, use)
  },
  teacherPage: async ({ browser, teacherSession }, use) => {
    await makeRolePage(browser, teacherSession, use)
  },
  otherTeacherPage: async ({ browser, otherTeacherSession }, use) => {
    await makeRolePage(browser, otherTeacherSession, use)
  },
  studentPage: async ({ browser, studentSession }, use) => {
    await makeRolePage(browser, studentSession, use)
  },
})

export { expect } from '@playwright/test'