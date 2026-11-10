import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { readAiResponse } from '../../src/utils/aiStream.js'
import { AUTO_MODE, isContestScene } from '../../src/utils/tutorModes.js'
import { ApiError } from '../../src/utils/apiError.js'

function review(response) {
  const source = readFileSync(new URL('../../src/views/submission/components/AiReviewDialog.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const startReview =')
  const end = source.indexOf('</script>', start)
  const messages = []
  const context = { props: { submissionId: 1, problemId: 1 }, reviewContent: { value: '' },
    reviewing: { value: false }, reviewIncomplete: { value: false }, reviewNotice: { value: '' }, question: { value: '' }, helpMode: { value: AUTO_MODE }, lastAnswer: '', isContestScene,
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

function profileLoader(getUserInfo) {
  const source = readFileSync(new URL('../../src/layout/ProfileLayout.vue', import.meta.url), 'utf8')
  const start = source.indexOf('async function loadUser()')
  const end = source.indexOf("provide('profileContext'", start)
  const context = { route: { params: { id: '1' } }, loading: { value: true }, hidden: { value: false }, generation: 0,
    error: { value: '' }, displayUser: { value: {} }, coverFailed: { value: false }, getUserInfo }
  vm.runInNewContext(source.slice(start, end) + '\nglobalThis.run = loadUser', context)
  return context
}
test('private profile stops loading after access is denied', async () => {
  const context = profileLoader(async () => { throw { response: { data: { code: 403 } } } })
  await context.run()
  assert.equal(context.hidden.value, true)
  assert.equal(context.loading.value, false)
  assert.match(context.error.value, /未公开主页/)
})
test('late profile response cannot replace another users identity or cover', async () => {
  const pending = new Map()
  const context = profileLoader(id => new Promise(resolve => pending.set(id, resolve)))
  const old = context.run(); context.route.params.id = '2'; const current = context.run()
  pending.get('2')({code:200,data:{id:2,coverUrl:'/uploads/new.png'}}); await current
  pending.get('1')({code:200,data:{id:1,coverUrl:'/uploads/old.png'}}); await old
  assert.equal(context.displayUser.value.id,2)
  assert.equal(context.displayUser.value.coverUrl,'/uploads/new.png')
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

test('partial AI configuration group save requests a config reload and does not report success', async () => {
 // 配置保存随 AI 管理页 Tab 重构迁移到 AiConfigPanel.vue，并改为分组保存。
 // 失败后由 emit('saved') 通知父组件回读实际配置，等价于原实现的回读语义。
 const source=readFileSync(new URL('../../src/views/admin/components/AiConfigPanel.vue',import.meta.url),'utf8')
 const start=source.indexOf('const saveGroup =');const end=source.indexOf('const promptKey =',start)
 let writes=0,reloads=0,successes=0
 const context={savingGroup:{value:''},configError:{value:''},editable:{first:'new',second:'new'},
  updateAiConfig:async()=>{if(++writes===2)throw new Error('合成保存失败');return {code:200}},
  isResultSuccess:res=>res.code===200,getResultMessage:res=>res.message,
  emit:event=>{if(event==='saved')reloads++},showApiError(){},ElMessage:{error(){},success(){successes++}}}
 vm.runInNewContext(source.slice(start,end)+'\nglobalThis.run = saveGroup',context)
 await assert.doesNotReject(context.run(['first','second'],'quota'));assert.equal(reloads,1);assert.equal(successes,0)
 assert.match(context.configError.value,/未完整保存/);assert.equal(context.savingGroup.value,'')
})


test('finishing a review preserves a followup typed while the answer streams', async () => {
  const page = review(new Response('data: 回答\n\nevent: done\ndata: \n\n', { headers: { 'Content-Type': 'text/event-stream' } }))
  const pending = page.run()
  page.context.question.value = '解释刚才提到的边界条件'
  await pending
  assert.equal(page.context.question.value, '解释刚才提到的边界条件')
})
