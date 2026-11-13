// 学习周报结构化数据契约（schemaVersion=1）。
//
// 后端 FreemarkerHtmlBuilder.render 用同一份 buildModel 结果同时渲染 HTML 与
// 结构化 JSON，并把 JSON 放在不执行的 <script type="application/json"> 节点里。
// 前端只读取该契约节点，绝不解析展示 DOM：模板图表由内联 SVG 脚本绘制，
// 从 DOM 反推数据是错误做法。节点缺失或解析失败时返回 null，阅读页降级原始版。

export const REPORT_DATA_NODE_ID = 'muse-report-data'

const SCRIPT_PATTERN = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi

function isObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function asText(value) {
  if (typeof value === 'string') return value
  return value == null ? '' : String(value)
}

function asNumber(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function asList(value) {
  return Array.isArray(value) ? value : []
}

function normalizeRadar(radar) {
  if (!isObject(radar) || !Array.isArray(radar.dimensions) || !Array.isArray(radar.values)) return null
  if (radar.dimensions.length === 0 || radar.dimensions.length !== radar.values.length) return null
  return {
    dimensions: radar.dimensions.map(asText),
    values: radar.values.map(asNumber)
  }
}

function normalizeComparison(comparison) {
  if (!isObject(comparison)) return null
  const { dimensions, base, growth } = comparison
  if (!Array.isArray(dimensions) || !Array.isArray(base) || !Array.isArray(growth)) return null
  if (dimensions.length === 0 || dimensions.length !== base.length || dimensions.length !== growth.length) return null
  return {
    dimensions: dimensions.map(asText),
    base: base.map(asNumber),
    growth: growth.map(asNumber)
  }
}

function normalize(data) {
  if (!isObject(data) || data.schemaVersion !== 1) return null
  if (!isObject(data.header) || !isObject(data.overview)) return null

  return {
    schemaVersion: 1,
    header: {
      studentName: asText(data.header.studentName),
      className: asText(data.header.className),
      periodStart: asText(data.header.periodStart),
      periodEnd: asText(data.header.periodEnd),
      weekNo: asNumber(data.header.weekNo)
    },
    overview: {
      totalSubmissions: asNumber(data.overview.totalSubmissions),
      acceptedProblemCount: asNumber(data.overview.acceptedProblemCount),
      activeDays: asNumber(data.overview.activeDays)
    },
    radar: normalizeRadar(data.radar),
    comparison: normalizeComparison(data.comparison),
    tagRows: asList(data.tagRows).map((row) => ({
      tagName: asText(row?.tagName),
      attempted: asNumber(row?.attempted),
      passed: asNumber(row?.passed),
      firstTryRate: asNumber(row?.firstTryRate),
      mastery: asNumber(row?.mastery),
      levelLabel: asText(row?.levelLabel)
    })),
    weakPoints: asList(data.weakPoints).map((item) => ({
      tagName: asText(item?.tagName),
      mastery: asNumber(item?.mastery)
    })),
    advice: asText(data.advice),
    recommendations: asList(data.recommendations).map((item) => ({
      tagName: asText(item?.tagName),
      title: asText(item?.title),
      difficultyLabel: asText(item?.difficultyLabel)
    }))
  }
}

/**
 * 从报告 HTML 中提取结构化数据。
 * 用字符串匹配而非 DOMParser：契约节点格式由后端固定，且不依赖浏览器 DOM，
 * 便于在 Node 单测中验证。后端已把 `<` 写成 `\u003c`，正文不会提前闭合节点。
 */
export function extractReportData(html) {
  if (typeof html !== 'string' || !html) return null
  SCRIPT_PATTERN.lastIndex = 0
  let match
  while ((match = SCRIPT_PATTERN.exec(html)) !== null) {
    const attributes = match[1] || ''
    if (!new RegExp(`id=["']${REPORT_DATA_NODE_ID}["']`, 'i').test(attributes)) continue
    if (!/type=["']application\/json["']/i.test(attributes)) return null
    try {
      return normalize(JSON.parse((match[2] || '').trim()))
    } catch {
      return null
    }
  }
  return null
}
