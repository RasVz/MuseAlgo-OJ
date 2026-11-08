/**
 * 题目难度的唯一分段定义。题目分数目前按 100 分步长录入。
 * 高段统一收敛，前中段保留更细的学习成长刻度。
 */
export const DIFFICULTY_RANGES = Object.freeze([
  { key: 'intro', label: '入门', min: 800, max: 900, color: '#64748b' },
  { key: 'intro-plus', label: '入门+', min: 1000, max: 1100, color: '#16a34a' },
  { key: 'universal-minus', label: '普及-', min: 1200, max: 1300, color: '#059669' },
  { key: 'universal', label: '普及', min: 1400, max: 1500, color: '#0ea5e9' },
  { key: 'universal-plus', label: '普及+', min: 1600, max: 1700, color: '#2563eb' },
  { key: 'improve-minus', label: '提高-', min: 1800, max: 1900, color: '#7c3aed' },
  { key: 'improve', label: '提高', min: 2000, max: 2100, color: '#c026d3' },
  { key: 'improve-plus', label: '提高+', min: 2200, max: 2300, color: '#ea580c' },
  { key: 'provincial-minus', label: '省选-', min: 2400, max: 2500, color: '#f97316' },
  { key: 'provincial-noi-minus', label: '省选 / NOI-', min: 2600, max: 2700, color: '#e11d48' },
  { key: 'noi-plus', label: 'NOI 及以上', min: 2800, max: 3000, color: '#ff1f1f' }
])

export function getDifficultyRange(score) {
  const normalizedScore = Number(score)
  if (!Number.isFinite(normalizedScore)) return null

  const highestRange = DIFFICULTY_RANGES[DIFFICULTY_RANGES.length - 1]
  if (normalizedScore > highestRange.max) return highestRange

  return DIFFICULTY_RANGES.find(
    ({ min, max }) => normalizedScore >= min && normalizedScore <= max
  ) || null
}

export function getDifficultyRangeByKey(key) {
  return DIFFICULTY_RANGES.find((range) => range.key === key) || null
}

export function getDifficultyColor(score) {
  return getDifficultyRange(score)?.color || '#94a3b8'
}

export function getDifficultyLabel(score) {
  return getDifficultyRange(score)?.label || '未评定'
}
