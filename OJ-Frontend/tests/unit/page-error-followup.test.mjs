import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { setImmediate } from 'node:timers'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { parse } = require('@babel/parser')
function load(file, names, context) {
 const source = readFileSync(new URL('../../src/' + file, import.meta.url), 'utf8').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
 const ast = parse(source, { sourceType: 'module' })
 for (const name of names) {
  const node = ast.program.body.flatMap(n => n.declarations || []).find(n => n.id.name === name)
  vm.runInNewContext(`globalThis.${name} = ${source.slice(node.init.start, node.init.end)}`, context)
 }
 return context
}
const pages = [
 ['views/problem-list/components/ProblemListInfoEdit.vue', 'initData', 'getProblemListDetail'],
 ['views/post/PostDetailView.vue', 'handleLike', 'togglePostLike'],
 ['views/post/PostDetailView.vue', 'handleFavorite', 'addFavorite'],
 ['views/problem/ProblemDetailView.vue', 'handleRealSubmit', 'submitCode'],
 ['views/admin/components/ProblemCaseManager.vue', 'handleUpload', 'uploadProblemCases']
]
for (const [file, name, api] of pages) for (const notified of [false, true]) {
 test(`${name} preserves the specific API error with notified=${notified}`, async () => {
  const messages = []; const error = Object.assign(new Error('合成具体原因'), { notified })
  const context = { props: { listId: 1, problemId: 1 }, currentId: 3, post: { value: { id: 1, isLiked: false, isFavorited: false } },
   problem: { value: { id: 1 } }, submitting: { value: false }, workspaceRef: { value: { getSubmissionData: () => ({ language: 'cpp', code: 'x' }) } },
   route: { query: {} }, FormData, console: { error() {} },
   ElMessage: { error: text => messages.push(text), warning() {}, success() {} },
   showApiError: (e, fallback) => { if (!e.notified && !e.authNotified) messages.push(e.message || fallback) },
   [api]: async () => { throw error } }
  load(file, [name], context)
  await context[name]({ file: new Blob(['synthetic']) })
  assert.deepEqual(messages, notified ? [] : ['合成具体原因'])
 })
}
test('removed problem is not sorted using a stale list when refresh fails', async () => {
 let sorts = 0
 const context = { props: { listId: 1 }, problemList: { value: [{ id: 2, title: 'synthetic' }] }, loadError: { value: '' },
  ElMessageBox: { confirm: async () => true }, ElMessage: { success() {}, error() {} }, console: { error() {} }, showApiError() {},
  removeProblemFromList: async () => ({}), getListProblems: async () => { throw new Error('刷新失败') },
  saveCurrentOrder: async () => { sorts++ } }
 load('views/problem-list/components/ProblemListProblemsEdit.vue', ['fetchProblems', 'removeProblem'], context)
 await context.removeProblem({ id: 2, title: 'synthetic' }); await new Promise(resolve => setImmediate(resolve))
 assert.equal(sorts, 0); assert.match(context.loadError.value, /刷新失败/)
 assert.equal(context.problemList.value.some(row => row.id === 2), false)
})
test('failed sort and failed recovery never claim the order was restored', async () => {
 const messages = []
 const context = { savingSort: { value: false }, allLists: { value: [{ id: 2 }, { id: 1 }] }, sortError: { value: '' },
  sortSaved: { value: false }, lastSavedIds: { value: null },
  updateProblemListSort: async () => { throw Object.assign(new Error('排序失败'), { notified: true }) },
  fetchList: async () => { throw Object.assign(new Error('刷新失败'), { notified: true }) }, showApiError() {},
  isResultSuccess: () => true, getResultMessage: (_res, fallback) => fallback,
  ElMessage: { error: text => messages.push(text), success() {} } }
 load('views/admin/AdminProblemListsView.vue', ['saveSort'], context)
 await context.saveSort([2, 1]); await new Promise(resolve => setImmediate(resolve))
 assert.equal(messages.some(text => text.includes('已恢复')), false)
 assert.match(context.sortError.value, /刷新失败/); assert.equal(context.savingSort.value, false)
})
test('notification scope failure cannot retain the prior scopes actionable rows', async () => {
 const context = { loading: { value: false }, loadError: { value: '' }, loadGeneration: 0, scope: { value: 'today' },
  items: { value: [{ id: 3, title: 'old all-scope row' }] }, getNotifications: async () => { throw new Error('范围读取失败') } }
 load('views/user/NotificationCenterView.vue', ['load'], context)
 await context.load(); assert.equal(context.items.value.length, 0); assert.equal(context.loadError.value, '范围读取失败')
})
test('pinning while a sort is saving cannot change unsaved local order', () => {
 let saves = 0
 const context = { savingSort: { value: true }, sortMode: { value: true }, filterActive: { value: false },
  allLists: { value: [{ id: 1 }, { id: 2 }] }, saveSort: () => { saves++ } }
 load('views/admin/AdminProblemListsView.vue', ['pinRow', 'applyOrder'], context)
 context.pinRow({ id: 2 }); assert.equal(context.allLists.value[0].id, 1); assert.equal(saves, 0)
})
test('drag callbacks cannot mutate order while an earlier save is pending', () => {
 let saves = 0
 const context = { savingSort: { value: true }, sortMode: { value: true }, filterActive: { value: false },
  allLists: { value: [{ id: 1 }, { id: 2 }] }, saveSort: () => { saves++ } }
 load('views/admin/AdminProblemListsView.vue', ['onReorder', 'applyOrder'], context)
 context.onReorder({ oldIndex: 0, newIndex: 1 })
 assert.equal(context.allLists.value[0].id, 1); assert.equal(saves, 0)
})
test('drag end only emits the reorder intent from the table component', () => {
 let options; const emitted = []
 const context = { props: { sortableActive: true }, tableRef: { value: { $el: { querySelector: () => ({}) } } },
  sortableInstance: null, destroySortable() {},
  Sortable: { create: (_el, value) => { options = value; return {} } },
  emit: (name, payload) => emitted.push([name, payload]) }
 load('views/admin/problem-lists/ProblemListTable.vue', ['initSortable'], context)
 context.initSortable()
 options.onEnd({ oldIndex: 0, newIndex: 1 })
 assert.equal(emitted.length, 1)
 assert.equal(emitted[0][0], 'reorder')
 assert.equal(emitted[0][1].oldIndex, 0)
 assert.equal(emitted[0][1].newIndex, 1)
})
