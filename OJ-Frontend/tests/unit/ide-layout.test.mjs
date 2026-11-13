import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_IDE_LAYOUT,
  IDE_LAYOUT_STORAGE_KEY,
  canDockRight,
  loadIdeLayout,
  parseIdeLayout,
  resolveStatementWidth,
  resolveTestSize,
  saveIdeLayout,
  statementWidthBounds,
  testSizeBounds
} from '../../src/utils/ideLayout.js'

const memoryStorage = () => {
  let value = null
  return {
    getItem: () => value,
    setItem: (key, next) => {
      assert.equal(key, IDE_LAYOUT_STORAGE_KEY)
      value = next
    }
  }
}

test('corrupted or partial layout falls back to safe values', () => {
  assert.deepEqual(parseIdeLayout('{not json'), { ...DEFAULT_IDE_LAYOUT })
  assert.deepEqual(parseIdeLayout(null), { ...DEFAULT_IDE_LAYOUT })
  assert.deepEqual(parseIdeLayout('{"dock":"right","statementRatio":0.99,"rightSize":10}'), {
    ...DEFAULT_IDE_LAYOUT,
    dock: 'right',
    statementRatio: 0.6,
    rightSize: 280
  })
})

test('layout survives a storage round trip', () => {
  const storage = memoryStorage()
  const layout = { dock: 'right', collapsed: true, statementRatio: 0.51, bottomSize: 300, rightSize: 420 }
  assert.equal(saveIdeLayout(storage, layout), true)
  assert.deepEqual(loadIdeLayout(storage), layout)
})

test('statement width reserves room for the test panel only when docked right', () => {
  const rightBounds = statementWidthBounds({
    containerWidth: 1440,
    dock: 'right',
    requestedTestSize: 360,
    collapsed: false
  })
  assert.equal(rightBounds.min, 320)
  assert.equal(rightBounds.max, 708)
  assert.ok(Math.abs(resolveStatementWidth({ containerWidth: 1440, ratio: 0.42, dock: 'right', requestedTestSize: 360 }) - 604.8) < 0.01)

  const bottomBounds = statementWidthBounds({ containerWidth: 1440, dock: 'bottom', requestedTestSize: 360 })
  assert.equal(bottomBounds.max, 864)
})

test('narrow right dock is clamped instead of squeezing the editor below its minimum', () => {
  const width = resolveStatementWidth({ containerWidth: 1024, ratio: 0.42, dock: 'right', requestedTestSize: 360 })
  assert.equal(width, 320)
  assert.equal(canDockRight(1024), true)
  assert.equal(canDockRight(1023), false)
})

test('test panel size follows dock orientation and leaves the editor usable', () => {
  const right = resolveTestSize({
    requestedSize: 360,
    dock: 'right',
    containerWidth: 1024,
    statementWidth: 320
  })
  assert.equal(right, 332)

  const bottom = resolveTestSize({
    requestedSize: 999,
    dock: 'bottom',
    containerHeight: 800,
    statementWidth: 604
  })
  assert.equal(bottom, 560)

  const bottomBounds = testSizeBounds({ dock: 'bottom', containerHeight: 800 })
  assert.deepEqual(bottomBounds, { min: 140, max: 560 })
})
