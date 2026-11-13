export function visibleDifficultyBuckets(rows, fullRange = false) {
  const sorted = rows
    .map((item) => ({ rating: Number(item.rating), count: Number(item.solved) || 0 }))
    .filter((item) => Number.isFinite(item.rating))
    .sort((a, b) => a.rating - b.rating)
  const nonzero = sorted.filter((item) => item.count > 0)
  if (fullRange || !nonzero.length) return sorted
  const from = Math.max(800, nonzero[0].rating - 100)
  const to = Math.min(3000, Math.max(from + 500, nonzero.at(-1).rating + 100))
  return sorted.filter((item) => item.rating >= from && item.rating <= to)
}
export function recentPracticeRows(rows, type) {
  const field = type === 'passed' ? 'solvedAt' : 'lastAttemptAt'
  const time = (row) => Date.parse(row[field]) || 0
  return [...rows].sort((a, b) => time(b) - time(a))
}
