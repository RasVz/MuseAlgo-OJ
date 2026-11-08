import test from 'node:test';
import assert from 'node:assert/strict';
import { deepClone } from '../../src/utils/deepClone.js';

test('deepClone copies plain data deeply', () => {
  const source = { a: 1, b: { c: 'x' }, d: [1, 2, { e: true }], f: null };
  const copy = deepClone(source);
  assert.deepEqual(copy, source);
  assert.notEqual(copy, source);
  assert.notEqual(copy.b, source.b);
  assert.notEqual(copy.d[2], source.d[2]);
});

test('deepClone handles Vue-like reactive proxy objects that break structuredClone', () => {
  // 模拟 Vue reactive 的 Proxy：structuredClone 会抛 DataCloneError，deepClone 必须可用
  const target = { title: 't', samples: [{ input: '1', output: '2' }] };
  const proxy = new Proxy(target, {});
  assert.throws(() => structuredClone(proxy)); // 佐证：原生 structuredClone 确实不能拷贝代理
  const copy = deepClone(proxy);
  assert.deepEqual(copy, target);
  assert.notEqual(copy, target);
});

test('deepClone passes through primitives and drops functions like JSON semantics', () => {
  assert.equal(deepClone(null), null);
  assert.equal(deepClone(5), 5);
  assert.equal(deepClone('s'), 's');
  const withFn = deepClone({ a: 1, fn: () => 1 });
  assert.deepEqual(withFn, { a: 1 });
});
