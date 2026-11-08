import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { readAiResponse } from '../../src/utils/aiStream.js'
import { ApiError } from '../../src/utils/apiError.js'

function review(response) {
  const source = readFileSync(new URL('../../src/views/submission/components/AiReviewDialog.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const startReview =')
  const end = source.indexOf('</script>', start)
  const messages = []
  const context = { props: { submissionId: 1, problemId: 1 }, reviewContent: { value: '' },
    reviewing: { value: false }, reviewIncomplete: { value: false },
    fetch: async () => response, useUserStore: () => ({ getAccessToken: () => 'synthetic' }),
    ElMessage: { error: text => messages.push(text) }, showApiError: (error, fallback) => { if (!error.notified && !error.authNotified) messages.push(error.message || fallback) }, console: { error() {} },
    TextDecoder, readAiResponse, AbortController, reviewController: null, reviewGeneration: 0 }
  vm.runInNewContext(source.slice(start, end) + '\nglobalThis.run = startReview', context)
  return { context, messages, run: context.run }
}
for (const [name, response, expected] of [
  ['JSON handshake', () => new Response(JSON.stringify({ code: 403, errorCode: 'ACCESS_DENIED', message: '无权复盘' }), { headers: { 'Content-Type': 'application/json' } }), '无权复盘'],
  ['app-error', () => new Response('data: 已生成部分\n\nevent: app-error\ndata: {"code":503,"message":"合成上游失败"}\n\n', { headers: { 'Content-Type': 'text/event-stream' } }), '合成上游失败'],
  ['premature EOF', () => new Response('data: 已生成部分\n\n', { headers: { 'Content-Type': 'text/event-stream' } }), 'AI 回复中断，请重试']
]) {
  test(`review ${name} keeps the failure reason and incomplete state`, async () => {
    const page = review(response()); await page.run()
    assert.deepEqual(page.messages, [expected]); assert.equal(page.context.reviewIncomplete.value, true)
    assert.equal(page.context.reviewing.value, false)
    assert.equal(page.context.reviewContent.value.includes('"code"'), false)
  })
}
test('review only completes after done', async () => {
  const page = review(new Response('data:   保留缩进\n\nevent: done\ndata: \n\n', { headers: { 'Content-Type': 'text/event-stream' } }))
  await page.run(); assert.equal(page.context.reviewContent.value, '  保留缩进')
  assert.equal(page.context.reviewIncomplete.value, false); assert.deepEqual(page.messages, [])
})
test('contest API failure never falls back to unrelated global submissions', async () => {
  const source = readFileSync(new URL('../../src/views/contest/ContestSubmissionView.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const requestWithFallback =')
  const end = source.indexOf('const parseResponse =', start)
  let primary = 0; let fallback = 0
  const failure = new ApiError('合成依赖故障', { code: 503 })
  const context = { buildParams: () => ({}), contestId: { value: 1 },
    getContestSubmissionList: async () => { primary++; throw failure },
    getContestSubmissionListFallbackA: async () => { fallback++; throw failure },
    getContestSubmissionListFallbackB: async () => { fallback++; return { code: 200, data: ['unrelated'] } } }
  vm.runInNewContext(source.slice(start, end) + '\nglobalThis.run = requestWithFallback', context)
  await assert.rejects(context.run(), error => error === failure)
  assert.equal(primary, 1); assert.equal(fallback, 0)
})

test('late submission detail response cannot overwrite a newer route', async () => {
  const source = readFileSync(new URL('../../src/views/submission/SubmissionDetailView.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const loadData =')
  const end = source.indexOf('const goBack =', start)
  const pending = new Map()
  const context = { route: { params: { id: '1' } }, loading: { value: false }, detail: { value: null },
    isInProgress: { value: false }, reviewDialogVisible: { value: false }, loadGeneration: 0,
    codeBlock: { value: null }, nextTick: callback => callback(), ElMessage: { error() {} },
    console: { error() {} }, ApiError, getSubmissionDetail: id => new Promise(resolve => pending.set(id, resolve)) }
  vm.runInNewContext(source.slice(start, end) + '\nglobalThis.run = loadData', context)
  const old = context.run(); context.route.params.id = '2'; const current = context.run()
  pending.get('2')({ code: 200, data: { id: 2, code: 'new' } }); await current
  pending.get('1')({ code: 200, data: { id: 1, code: 'old' } }); await old
  assert.equal(context.detail.value.id, 2)
})

test('private profile stops loading after access is denied', async () => {
  const source = readFileSync(new URL('../../src/views/user/ProfileView.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const fetchData =')
  const end = source.indexOf('// 四组数据', start)
  const context = { route: { params: { id: '1' } }, loading: { value: true }, profileHidden: { value: false }, loadGeneration: 0,
    stats: { value: {} }, radarData: { value: [] }, recentAC: { value: [] }, ratingHistory: { value: [] },
    console: { error() {} }, getUserInfo: async () => { throw { response: { data: { code: 403 } } } } }
  vm.runInNewContext(source.slice(start, end) + '\nglobalThis.run = fetchData', context)
  await context.run(); assert.equal(context.profileHidden.value, true); assert.equal(context.loading.value, false)
})

test('settings do not repeat an error already shown by the shared request layer', async () => {
  const source = readFileSync(new URL('../../src/views/user/composables/useUserSettings.js', import.meta.url), 'utf8')
  const start = source.indexOf('const initProfile =')
  const end = source.indexOf('//', source.indexOf('\n  }', start) + 4)
  const messages = []
  const context = { userStore: { userInfo: { id: 1 } }, fillForm() {}, fillPref() {}, syncLocalUser() {},
    getUserInfo: async () => { throw Object.assign(new Error('具体原因'), { notified: true }) },
    getUserPreferences: async () => ({ code: 200, data: {} }), ElMessage: { error: message => messages.push(message) },
    console: { error() {} }, showApiError: (error, fallback) => { if (!error.notified && !error.authNotified) messages.push(error.message || fallback) } }
  vm.runInNewContext(source.slice(start, end) + '\nglobalThis.run = initProfile', context)
  await context.run(); assert.deepEqual(messages, [])
})

for (const [file, name, next, api] of [
  ['FavoriteView.vue', 'fetchPosts', 'const fetchProblemLists', 'getFavoritePosts'],
  ['ReviewBookView.vue', 'load', 'const changeScope', 'getReviewBook']
]) {
  test(`${file} keeps failed loading distinct from an empty result`, async () => {
    const source = readFileSync(new URL('../../src/views/user/' + file, import.meta.url), 'utf8')
    const start = source.indexOf(`const ${name} =`); const end = source.indexOf(next, start)
    const context = { loading: { value: false }, loadError: { value: '' }, loadGeneration: 0,
      activeTab: { value: 'post' }, scope: { value: 'all' }, postList: { value: [] }, items: { value: [] },
      counts: { value: {} }, console: { error() {} }, [api]: async () => { throw new Error('合成服务失败') } }
    vm.runInNewContext(source.slice(start, end) + `\nglobalThis.run = ${name}`, context)
    await context.run(); assert.equal(context.loadError.value, '合成服务失败'); assert.equal(context.loading.value, false)
  })
}

test('partial AI configuration save reads back committed values and does not report success', async () => {
 const source=readFileSync(new URL('../../src/views/admin/AdminAiView.vue',import.meta.url),'utf8')
 const start=source.indexOf('const saveConfig =');const end=source.indexOf('const queryQuota',start)
 let writes=0,reads=0,successes=0
 const context={savingConfig:{value:false},editableConfig:{value:{first:'new',second:'new'}},serviceManaged:{value:false},config:{value:{}},configSaveError:{value:''},
  updateAiConfig:async()=>{if(++writes===2)throw Object.assign(new Error('合成保存失败'),{notified:true});return {code:200}},
  getAiConfig:async()=>{reads++;return {code:200,data:{quotaEnabled:false}}},isResultSuccess:res=>res.code===200,unwrapResultData:res=>res.data,
  getResultMessage:res=>res.message, syncEditableConfig(){},showApiError(){},ElMessage:{error(){},success(){successes++}}}
 vm.runInNewContext(source.slice(start,end)+'\nglobalThis.run = saveConfig',context)
 await assert.doesNotReject(context.run());assert.equal(reads,1);assert.equal(successes,0)
 assert.match(context.configSaveError.value,/未完整保存/);assert.equal(context.savingConfig.value,false)
})
