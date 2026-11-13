export const CLASS_MEMBER_FILTERS = ['all', 'hasMembers', 'empty']
export const CLASS_SORTS = ['newest', 'oldest', 'name']
export const CLASS_PAGE_SIZES = [10, 20, 50, 100]

const textValue = (value) => (typeof value === 'string' ? value : '')

export function normalizeClassesQuery(query = {}) {
  const rawPage = textValue(query.page)
  const page = /^\d+$/.test(rawPage) ? Number(rawPage) : 1
  const size = Number(textValue(query.size))
  return {
    keyword: textValue(query.keyword).trim().slice(0, 100),
    page: Number.isSafeInteger(page) && page >= 1 && page <= 2147483647 ? page : 1,
    size: CLASS_PAGE_SIZES.includes(size) ? size : 20,
    memberStatus: CLASS_MEMBER_FILTERS.includes(query.memberStatus) ? query.memberStatus : 'all',
    sort: CLASS_SORTS.includes(query.sort) ? query.sort : 'newest',
  }
}

export function classesRouteQuery(state) {
  const query = {}
  if (state.keyword) query.keyword = state.keyword
  if (state.memberStatus !== 'all') query.memberStatus = state.memberStatus
  if (state.sort !== 'newest') query.sort = state.sort
  if (state.size !== 20) query.size = String(state.size)
  if (state.page !== 1) query.page = String(state.page)
  return query
}

export function classTeacherLabel(row) {
  return (
    row.teacherName?.trim() ||
    (row.teacherId != null ? `教师账号 #${row.teacherId}` : '教师信息缺失')
  )
}

export function classCreatedLabel(value) {
  return typeof value === 'string' && value ? value.replace('T', ' ').slice(0, 16) : '时间未记录'
}
