// 学习周报结构化数据契约（schemaVersion=1）的提取与降级边界。
// 前端只读取后端写入的 JSON 契约节点，不解析展示 DOM。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { extractReportData, REPORT_DATA_NODE_ID } from '../../src/views/admin/report/composables/reportData.js'

const NODE_OPEN = `<script type="application/json" id="${REPORT_DATA_NODE_ID}">`

function wrap(data) {
  return `<html><body><div class="section">报告正文</div>${NODE_OPEN}${JSON.stringify(data)}</script></body></html>`
}

function baseData() {
  return {
    schemaVersion: 1,
    header: {
      studentName: '测试学生', className: '算法基础班',
      periodStart: '2026-11-02', periodEnd: '2026-11-08', weekNo: 45
    },
    overview: { totalSubmissions: 12, acceptedProblemCount: 8, activeDays: 5 },
    radar: { dimensions: ['算法基础', '数据结构'], values: [80, 60] },
    comparison: { dimensions: ['数学', '图论'], base: [30, 40], growth: [10, 5] },
    tagRows: [{ tagName: '贪心', attempted: 5, passed: 4, firstTryRate: 60, mastery: 72, levelLabel: '良好' }],
    weakPoints: [{ tagName: '动态规划', mastery: 35 }],
    advice: '建议加强动态规划练习',
    recommendations: [{ tagName: '图论', title: '最短路练习', difficultyLabel: '中等' }]
  }
}

test('从契约节点提取并归一化完整报告数据', () => {
  const data = extractReportData(wrap(baseData()))
  assert.ok(data)
  assert.equal(data.schemaVersion, 1)
  assert.equal(data.header.studentName, '测试学生')
  assert.equal(data.header.weekNo, 45)
  assert.equal(data.overview.totalSubmissions, 12)
  assert.deepEqual(data.radar.values, [80, 60])
  assert.deepEqual(data.comparison.growth, [10, 5])
  assert.equal(data.tagRows[0].levelLabel, '良好')
  assert.equal(data.weakPoints[0].tagName, '动态规划')
  assert.equal(data.advice, '建议加强动态规划练习')
  assert.equal(data.recommendations[0].title, '最短路练习')
})

test('无契约节点、错误 type、版本不符或非法 JSON 均返回 null', () => {
  assert.equal(extractReportData(''), null)
  assert.equal(extractReportData(null), null)
  assert.equal(extractReportData('<html><body><div>只有展示 DOM</div></body></html>'), null)
  assert.equal(
    extractReportData(`<script id="${REPORT_DATA_NODE_ID}">${JSON.stringify(baseData())}</script>`),
    null
  )
  assert.equal(extractReportData(`${NODE_OPEN}{broken json}</script>`), null)
  const wrongVersion = baseData()
  wrongVersion.schemaVersion = 2
  assert.equal(extractReportData(wrap(wrongVersion)), null)
  const missingHeader = baseData()
  delete missingHeader.header
  assert.equal(extractReportData(wrap(missingHeader)), null)
})

test('不解析展示 DOM，只取契约节点', () => {
  const html = `<html><body>
    <script>var report = {"schemaVersion":1,"header":{},"overview":{}}</script>
    <div id="fake">${JSON.stringify(baseData())}</div>
    ${NODE_OPEN}${JSON.stringify(baseData())}</script>
  </body></html>`
  assert.equal(extractReportData(html).header.studentName, '测试学生')
})

test('正文中的 </script> 以 \\u003c 转义后不会提前闭合契约节点', () => {
  const data = baseData()
  data.advice = '注意 </script> 这类文本不能破坏节点'
  const json = JSON.stringify(data).replace(/</g, '\\u003c')
  const html = `<html><body>${NODE_OPEN}${json}</script></body></html>`
  const result = extractReportData(html)
  assert.ok(result)
  assert.equal(result.advice, '注意 </script> 这类文本不能破坏节点')
})

test('图表数据形状不合法时该板块降级为 null，其余板块仍可用', () => {
  const data = baseData()
  data.radar = { dimensions: ['A', 'B'], values: [1] }
  data.comparison = { dimensions: ['A'], base: [1, 2], growth: [1] }
  const result = extractReportData(wrap(data))
  assert.ok(result)
  assert.equal(result.radar, null)
  assert.equal(result.comparison, null)
  assert.equal(result.overview.acceptedProblemCount, 8)
  assert.equal(result.tagRows.length, 1)
})

test('缺失可选字段按空值归一化，不抛异常', () => {
  const data = {
    schemaVersion: 1,
    header: { studentName: '甲', periodStart: '2026-11-02', periodEnd: '2026-11-08' },
    overview: { totalSubmissions: 0 }
  }
  const result = extractReportData(wrap(data))
  assert.ok(result)
  assert.equal(result.header.className, '')
  assert.equal(result.header.weekNo, 0)
  assert.equal(result.overview.acceptedProblemCount, 0)
  assert.equal(result.advice, '')
  assert.deepEqual(result.tagRows, [])
  assert.deepEqual(result.recommendations, [])
  assert.equal(result.radar, null)
})
