import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRatingTimeline } from '../../src/utils/ratingTimeline.js'

test('new accounts have a dated default rating point without a fabricated contest', () => {
  const points = createRatingTimeline([], '2026-11-10T08:00:00')
  assert.equal(points.length, 1)
  assert.equal(points[0].rating, 1200)
  assert.equal(points[0].date, '2026-11-10T08:00:00')
  assert.equal(points[0].kind, 'registration')
  assert.equal(points[0].contestId, null)
})
test('contest history follows the registration point and remains unchanged', () => {
  const history = [{ contestId: 5, rating: 1160, ratingChange: -40, date: '2026-11-11T10:00:00' }]
  const points = createRatingTimeline(history, '2026-11-10T08:00:00')
  assert.deepEqual(
    points.map((point) => point.rating),
    [1200, 1160],
  )
  assert.equal(points[1].contestId, 5)
  assert.equal(history[0].kind, undefined)
})
test('missing registration date stays unknown rather than inventing a date', () => {
  assert.equal(createRatingTimeline([])[0].date, null)
})
