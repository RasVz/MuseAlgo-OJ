/**
 * 页面内存中的样例与运行结果，不写本地持久存储或题目数据库。
 *
 * 自测样例只保存输入；输出栏由最近一次运行结果提供，不做期望值比较。
 * 官方样例的输入不可修改，题面正文使用另一份只读快照；
 * 官方样例不可删除，自定义样例可编辑和删除。
 */
const OFFICIAL = 'OFFICIAL'
const AI_VERIFIED = 'AI_VERIFIED'
const EDITABLE_FIELDS = ['input', 'note']

export function createLocalTestCases(official = [], onChange = () => {}) {
  let sequence = 0
  let selectedId = null
  let items = official.map(sample => createOfficialItem(sample))
  selectedId = items[0]?.id ?? null
  const runs = new Map()

  function createOfficialItem(sample) {
    return {
      id: `official-${++sequence}`,
      input: sample.input ?? '',
      source: OFFICIAL,
      note: ''
    }
  }

  const publish = () => onChange([...items], selectedId)
  const get = id => {
    const item = items.find(candidate => candidate.id === id)
    if (!item) throw new Error('样例已不存在')
    return item
  }
  const signature = (item, snapshot) => JSON.stringify([snapshot.code, snapshot.language, item.input])

  const model = {
    get items() {
      return items
    },
    get selectedId() {
      return selectedId
    },
    select(id) {
      get(id)
      selectedId = id
      publish()
    },
    add({ input = '', note = '', source = 'CUSTOM' } = {}) {
      if (items.length >= 30) throw new Error('当前页面最多保留 30 个样例')
      const id = `local-${++sequence}`
      items = [...items, { id, input, note, source }]
      selectedId = id
      publish()
      return id
    },
    /** 原地修改输入；AI 实测样例一旦手改即降级为自定义。 */
    patch(id, patch = {}) {
      const item = get(id)
      if (item.source === OFFICIAL && patch.input !== undefined && String(patch.input ?? '') !== item.input) {
        throw new Error('官方样例的输入不可修改')
      }
      let changed = false
      for (const field of EDITABLE_FIELDS) {
        if (patch[field] === undefined) continue
        const next = String(patch[field] ?? '')
        if (item[field] !== next) {
          item[field] = next
          changed = true
        }
      }
      if (!changed) return item

      if (item.source === AI_VERIFIED) {
        item.source = 'CUSTOM'
        item.note = '已手动编辑，原 AI 验证不再适用。' + item.note
      }
      runs.delete(id)
      publish()
      return item
    },
    // 兼容既有调用方：编辑语义已统一到 patch。
    edit(id, patch) {
      return model.patch(id, patch)
    },
    remove(id) {
      const item = get(id)
      if (item.source === OFFICIAL) throw new Error('官方样例不可删除')
      items = items.filter(candidate => candidate.id !== id)
      runs.delete(id)
      if (selectedId === id) selectedId = items[0]?.id ?? null
      publish()
    },
    record(id, snapshot, result) {
      const item = get(id)
      runs.set(id, { signature: signature(item, snapshot), result })
      publish()
    },
    result(id, snapshot) {
      const item = get(id)
      const cached = runs.get(id)
      if (!cached || cached.signature !== signature(item, snapshot)) return null
      const result = cached.result
      return {
        ...result,
        actual: result.raw_stdout ?? result.stdout ?? ''
      }
    },
    receipts(snapshot) {
      return items
        .map(item => model.result(item.id, snapshot)?.runId)
        .filter(Boolean)
        .slice(-5)
    },
    context() {
      const item = items.find(candidate => candidate.id === selectedId)
      return item
        ? {
            input: item.input,
            expectedOutput: null,
            hasExpected: false,
            note: item.note
          }
        : null
    },
    addCounterexample(event) {
      if (!event.confirmedFailure || !['OFFICIAL_SAMPLE', 'REFERENCE_PROGRAM', 'RUNTIME_ERROR'].includes(event.expectationSource)) {
        return null
      }
      const existing = items.find(item => item.input === event.input)
      const id =
        existing?.id ??
        model.add({
          input: event.input,
          source: AI_VERIFIED,
          note: (event.note || '实测反例') + '（针对提问时的代码版本）'
        })
      if (existing) model.select(id)
      if (event.snapshot && event.runId) {
        model.record(id, event.snapshot, {
          runId: event.runId,
          status: event.status,
          raw_stdout: event.stdout ?? event.actualOutput ?? '',
          stdout: event.stdout ?? event.actualOutput ?? '',
          stderr: event.stderr ?? '',
          compile_output: event.compile_output ?? '',
          truncated: Boolean(event.truncated),
          message: event.message ?? ''
        })
      }
      return id
    }
  }
  return model
}
