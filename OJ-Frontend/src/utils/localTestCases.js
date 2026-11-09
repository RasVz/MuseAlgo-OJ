/** 页面内存中的样例与运行结果，不写本地持久存储或题目数据库。 */
export function createLocalTestCases(official = [], onChange = () => {}) {
  let sequence = 0
  let selectedId = null
  let items = official.map(sample => ({ id: `official-${++sequence}`, input: sample.input ?? '',
    expectedOutput: sample.output ?? '', hasExpected: sample.output !== undefined && sample.output !== null,
    source: 'OFFICIAL', note: '' }))
  selectedId = items[0]?.id ?? null
  const runs = new Map()
  const publish = () => onChange([...items], selectedId)
  const get = id => { const item = items.find(x => x.id === id); if (!item) throw new Error('样例已不存在'); return item }
  const signature = (item, snapshot) => JSON.stringify([snapshot.code, snapshot.language, item.input, item.hasExpected, item.expectedOutput])
  const mutable = id => { const item = get(id); if (item.source === 'OFFICIAL') throw new Error('官方样例只读，请新增自定义样例'); return item }
  const model = {
    get items() { return items }, get selectedId() { return selectedId },
    select(id) { get(id); selectedId = id; publish() },
    add({ input = '', expectedOutput = '', hasExpected = false, note = '', source = 'CUSTOM' } = {}) {
      if (items.length >= 30) throw new Error('当前页面最多保留 30 个样例')
      const id = `local-${++sequence}`
      items = [...items, { id, input, expectedOutput, hasExpected, note, source }]; selectedId = id; publish(); return id
    },
    edit(id, patch) { const item = mutable(id); Object.assign(item, { input: patch.input ?? item.input,
      expectedOutput: patch.expectedOutput ?? item.expectedOutput, hasExpected: patch.hasExpected ?? item.hasExpected,
      note: patch.note ?? item.note }); if (item.source === 'AI_VERIFIED') { item.source = 'CUSTOM'; item.note = '已手动编辑，原 AI 验证不再适用。' + item.note } runs.delete(id); publish() },
    remove(id) { mutable(id); items = items.filter(x => x.id !== id); runs.delete(id);
      if (selectedId === id) selectedId = items[0]?.id ?? null; publish() },
    record(id, snapshot, result) { const item = get(id); runs.set(id, { signature: signature(item, snapshot), result }); publish() },
    result(id, snapshot) { const item = get(id), cached = runs.get(id); if (!cached || cached.signature !== signature(item, snapshot)) return null;
      const result = cached.result, actual = result.raw_stdout ?? result.stdout ?? '';
      return { ...result, actual, matches: !result.truncated && item.hasExpected && Number(result.status) === 3 ? actual.trimEnd() === item.expectedOutput.trimEnd() : null } },
    receipts(snapshot) { return items.map(item => model.result(item.id, snapshot)?.runId).filter(Boolean).slice(-5) },
    context() { const item = items.find(x => x.id === selectedId); return item ? { input: item.input,
      expectedOutput: item.hasExpected ? item.expectedOutput : null, hasExpected: item.hasExpected, note: item.note } : null },
    addCounterexample(event) {
      if (!event.confirmedFailure || !['OFFICIAL_SAMPLE','REFERENCE_PROGRAM','RUNTIME_ERROR'].includes(event.expectationSource)) return null
      const hasExpected = event.expectedOutput !== null && event.expectedOutput !== undefined
      const existing = items.find(item => item.input === event.input && item.hasExpected === hasExpected && (!hasExpected || item.expectedOutput === event.expectedOutput))
      const id = existing?.id ?? model.add({ input: event.input, expectedOutput: event.expectedOutput ?? '', hasExpected,
        source: 'AI_VERIFIED', note: (event.note || '实测反例') + '（针对提问时的代码版本）' })
      if (existing) model.select(id)
      if (event.snapshot && event.runId) model.record(id, event.snapshot, { runId: event.runId, status: event.status,
        raw_stdout: event.stdout ?? event.actualOutput ?? '', stdout: event.stdout ?? event.actualOutput ?? '', stderr: event.stderr ?? '', compile_output: event.compile_output ?? '', truncated: Boolean(event.truncated), message: event.message ?? '' })
      return id
    }
  }
  return model
}
