import { DIFFICULTY_RANGES } from '@/utils/difficulty'

// 兼容旧的常量入口；新代码直接使用 DIFFICULTY_RANGES。
export { DIFFICULTY_RANGES }
export const DIFFICULTY_LEVELS = DIFFICULTY_RANGES.map(({ key }) => key)
export const DIFFICULTY_LABEL_MAP = Object.fromEntries(
  DIFFICULTY_RANGES.map(({ key, label }) => [key, label])
)
export const DIFFICULTY_COLORS = Object.fromEntries(
  DIFFICULTY_RANGES.map(({ key, color }) => [key, color])
)
