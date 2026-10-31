/**
 * 深拷贝纯数据对象（JSON 语义）。
 *
 * 为什么不用 structuredClone：Vue 的 reactive()/ref() 返回 Proxy 代理对象，
 * 代理无法被结构化克隆，Chromium 会直接抛
 * `DataCloneError: Failed to execute 'structuredClone'`，导致整页崩溃。
 * 2026-10-30 浏览器联调在题目编辑页实际复现（ProblemBasicForm 拿到的
 * props.form 是父组件的 reactive 代理）。
 *
 * 表单、偏好这类纯 JSON 数据用 JSON 序列化拷贝足够；函数与 undefined 字段
 * 会被顺带丢弃，这正是表单场景想要的语义。
 */
export function deepClone(value) {
  if (value === null || typeof value !== 'object') {
    return value
  }
  return JSON.parse(JSON.stringify(value))
}
