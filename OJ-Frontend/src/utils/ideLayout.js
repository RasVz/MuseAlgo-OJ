/**
 * 题目详情页 IDE 布局的纯计算与持久化。
 *
 * 组件只负责测量容器、读写 CSS；所有边界收紧都在这里，便于单测覆盖：
 * - 题面宽度按容器比例保存，窗口变化时仍保持相对视觉比例；
 * - 自测面板在底部停靠时存高度、右侧停靠时存宽度，两种模式互不串值；
 * - 读取本地存储时一律经过校正，损坏或被手改的数据回退默认值。
 */
export const IDE_LAYOUT_STORAGE_KEY = 'musealgo:problem-ide-layout:v1'

export const IDE_LAYOUT_LIMITS = Object.freeze({
  splitter: 6,
  statementMin: 320,
  statementRatioMin: 0.24,
  statementRatioMax: 0.6,
  editorMin: 360,
  editorMinHeight: 220,
  testBottomMin: 140,
  testBottomMaxRatio: 0.7,
  testRightMin: 280,
  testRightMax: 560,
  minRightDockWidth: 1024
})

export const DEFAULT_IDE_LAYOUT = Object.freeze({
  dock: 'bottom',
  collapsed: false,
  statementRatio: 0.42,
  bottomSize: 280,
  rightSize: 360
})

export function clampNumber(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.min(Math.max(number, min), max)
}

export function parseIdeLayout(raw) {
  if (!raw || typeof raw !== 'string') return { ...DEFAULT_IDE_LAYOUT }
  let data
  try {
    data = JSON.parse(raw)
  } catch {
    return { ...DEFAULT_IDE_LAYOUT }
  }
  if (!data || typeof data !== 'object') return { ...DEFAULT_IDE_LAYOUT }

  return {
    dock: data.dock === 'right' ? 'right' : 'bottom',
    collapsed: data.collapsed === true,
    statementRatio: clampNumber(
      data.statementRatio,
      IDE_LAYOUT_LIMITS.statementRatioMin,
      IDE_LAYOUT_LIMITS.statementRatioMax,
      DEFAULT_IDE_LAYOUT.statementRatio
    ),
    bottomSize: clampNumber(data.bottomSize, IDE_LAYOUT_LIMITS.testBottomMin, 1600, DEFAULT_IDE_LAYOUT.bottomSize),
    rightSize: clampNumber(data.rightSize, IDE_LAYOUT_LIMITS.testRightMin, 1200, DEFAULT_IDE_LAYOUT.rightSize)
  }
}

export function loadIdeLayout(storage) {
  if (!storage || typeof storage.getItem !== 'function') return { ...DEFAULT_IDE_LAYOUT }
  try {
    return parseIdeLayout(storage.getItem(IDE_LAYOUT_STORAGE_KEY))
  } catch {
    return { ...DEFAULT_IDE_LAYOUT }
  }
}

export function saveIdeLayout(storage, layout) {
  if (!storage || typeof storage.setItem !== 'function') return false
  try {
    storage.setItem(IDE_LAYOUT_STORAGE_KEY, JSON.stringify({
      dock: layout?.dock === 'right' ? 'right' : 'bottom',
      collapsed: layout?.collapsed === true,
      statementRatio: clampNumber(
        layout?.statementRatio,
        IDE_LAYOUT_LIMITS.statementRatioMin,
        IDE_LAYOUT_LIMITS.statementRatioMax,
        DEFAULT_IDE_LAYOUT.statementRatio
      ),
      bottomSize: clampNumber(layout?.bottomSize, IDE_LAYOUT_LIMITS.testBottomMin, 1600, DEFAULT_IDE_LAYOUT.bottomSize),
      rightSize: clampNumber(layout?.rightSize, IDE_LAYOUT_LIMITS.testRightMin, 1200, DEFAULT_IDE_LAYOUT.rightSize)
    }))
    return true
  } catch {
    return false
  }
}

/**
 * 右侧停靠时，题面最大宽度必须给"自测 + 分隔条 + 编辑区最小宽度"让位；
 * 底部停靠时只需给编辑区最小宽度让位。
 */
export function statementWidthBounds({ containerWidth, dock, requestedTestSize = 0, collapsed = false }) {
  const limits = IDE_LAYOUT_LIMITS
  const reservedTest = dock === 'right' && !collapsed ? requestedTestSize + limits.splitter : 0
  const max = Math.max(
    limits.statementMin,
    Math.min(
      containerWidth * limits.statementRatioMax,
      containerWidth - reservedTest - limits.splitter - limits.editorMin
    )
  )
  return { min: limits.statementMin, max }
}

export function resolveStatementWidth({ containerWidth, ratio, ...context }) {
  const { min, max } = statementWidthBounds({ containerWidth, ...context })
  const requested = containerWidth * clampNumber(
    ratio,
    IDE_LAYOUT_LIMITS.statementRatioMin,
    IDE_LAYOUT_LIMITS.statementRatioMax,
    DEFAULT_IDE_LAYOUT.statementRatio
  )
  return Math.min(Math.max(requested, min), max)
}

export function testSizeBounds({ dock, containerWidth = 0, containerHeight = 0, statementWidth = 0 }) {
  const limits = IDE_LAYOUT_LIMITS
  if (dock === 'right') {
    const available = containerWidth - statementWidth - limits.splitter - limits.editorMin - limits.splitter
    return {
      min: limits.testRightMin,
      max: Math.max(limits.testRightMin, Math.min(limits.testRightMax, available))
    }
  }
  const available = containerHeight - limits.editorMinHeight - limits.splitter
  return {
    min: limits.testBottomMin,
    max: Math.max(limits.testBottomMin, Math.min(containerHeight * limits.testBottomMaxRatio, available))
  }
}

export function resolveTestSize({ requestedSize, ...context }) {
  const { min, max } = testSizeBounds(context)
  return Math.min(Math.max(Number(requestedSize) || min, min), max)
}

export function canDockRight(containerWidth) {
  return Number(containerWidth) >= IDE_LAYOUT_LIMITS.minRightDockWidth
}
