// 批量受理学习周报的纯函数：选择归一化、分批、结果映射与汇总。
//
// 与 UI 和请求解耦，便于 Node 单元测试；「受理」不等于「已生成」，
// PENDING / GENERATING 属于正常进行态，只有 GENERATED 才能打开正文。

/** 后端 BatchReportRequest 的单次上限（@Size max = 50）。 */
export const BATCH_LIMIT = 50

/**
 * 学生选择弹窗的搜索：按班级备注、用户名、邮箱或 ID 做包含匹配。
 * 空关键字返回原数组（不复制），避免每次输入都重建列表引用。
 * @param {object[]} members
 * @param {string} keyword
 * @returns {object[]}
 */
export function filterStudents(members, keyword) {
  const list = Array.isArray(members) ? members : []
  const normalized = String(keyword || '').trim().toLowerCase()
  if (!normalized) return list
  return list.filter((member) => {
    const remark = typeof member?.remark === 'string' ? member.remark.trim() : ''
    const label = remark || member?.username || ''
    return [label, member?.username, member?.email, member?.userId]
      .some((value) => String(value ?? '').toLowerCase().includes(normalized))
  })
}

/**
 * 归一化待受理学生：去重、剔除非法 ID，并给出稳定显示名。
 * @param {{ studentId?: number|string, userId?: number|string, name?: string }[]} students
 * @returns {{ studentId: number, name: string }[]}
 */
export function normalizeStudents(students) {
  const seen = new Set()
  const result = []
  for (const student of Array.isArray(students) ? students : []) {
    const studentId = Number(student?.studentId ?? student?.userId)
    if (!Number.isInteger(studentId) || studentId <= 0 || seen.has(studentId)) continue
    seen.add(studentId)
    const name = typeof student?.name === 'string' ? student.name.trim() : ''
    result.push({ studentId, name: name || `学生 #${studentId}` })
  }
  return result
}

/**
 * 按后端上限分批；顺序与学生选择顺序一致。
 * @param {{ studentId: number, name: string }[]} students
 * @param {number} [size]
 * @returns {{ studentId: number, name: string }[][]}
 */
export function chunkStudents(students, size = BATCH_LIMIT) {
  const limit = Number.isInteger(size) && size > 0 ? size : BATCH_LIMIT
  const chunks = []
  for (let index = 0; index < students.length; index += limit) {
    chunks.push(students.slice(index, index + limit))
  }
  return chunks
}

function failureCodeOf(result) {
  return result?.errorCode || result?.error || ''
}

/**
 * 批量接口单条结果 -> 本地任务行。
 * 后端契约：accepted=true 表示已受理（可能是 PENDING/GENERATING），
 * success=true 才表示已生成；status 缺失且未受理视为 REJECTED。
 */
export function normalizeBatchResult(result, student) {
  const status = result?.status || (result?.success === true ? 'GENERATED' : 'REJECTED')
  const accepted = result?.accepted === true
  const rejected = !accepted && status !== 'FAILED'
  const failureCode = failureCodeOf(result)
  return {
    studentId: student.studentId,
    name: student.name,
    jobId: result?.jobId ?? null,
    status,
    accepted,
    rejected,
    attempts: 0,
    canRetry: false,
    failure: status === 'FAILED' ? { code: failureCode, safeMessage: '' } : null,
    errorMessage: accepted ? '' : failureCode,
    retrying: false
  }
}

/** 未受理/请求失败的任务行。 */
export function rejectedItem(student, message) {
  return {
    studentId: student.studentId,
    name: student.name,
    jobId: null,
    status: 'REJECTED',
    accepted: false,
    rejected: true,
    attempts: 0,
    canRetry: false,
    failure: null,
    errorMessage: message || '未受理',
    retrying: false
  }
}

/**
 * 用报告任务详情/列表行更新本地任务行。
 * 列表行同样不含 html，这里只同步状态域字段。
 */
export function mergeJobIntoItem(item, job) {
  if (!item || !job) return item
  item.jobId = job.id ?? item.jobId
  item.status = job.status || item.status
  item.accepted = item.status !== 'FAILED' && !item.rejected
  item.attempts = job.attempts ?? item.attempts ?? 0
  item.canRetry = job.status === 'FAILED' && job.canRetry === true
  item.failure = job.generationFailure || null
  item.errorMessage = job.errorMessage || ''
  return item
}

/** 汇总当前批次状态，供卡片计数与受理后提示使用。 */
export function summarizeBatchItems(items) {
  const summary = { total: 0, accepted: 0, queued: 0, generated: 0, failed: 0, rejected: 0 }
  for (const item of Array.isArray(items) ? items : []) {
    summary.total += 1
    if (item.rejected || item.status === 'REJECTED') {
      summary.rejected += 1
    } else if (item.status === 'FAILED') {
      summary.accepted += 1
      summary.failed += 1
    } else {
      summary.accepted += 1
      if (item.status === 'GENERATED') summary.generated += 1
      else summary.queued += 1
    }
  }
  return summary
}
