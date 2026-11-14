export const USER_ROLE_FILTERS = ['all', '1', '2', '3']
export const USER_STATUS_FILTERS = ['all', 'active', 'banned']
export const USER_SORTS = ['id', 'createTime', 'lastLoginTime', 'submitCount']
export const USER_PAGE_SIZES = [20, 50, 100]

const textValue = (value) => (typeof value === 'string' ? value : '')

export function normalizeUserQuery(query = {}) {
  const rawPage = textValue(query.page)
  const page = /^\d+$/.test(rawPage) ? Number(rawPage) : 1
  const size = Number(textValue(query.size))
  const roleValue = textValue(query.role)
  return {
    keyword: textValue(query.keyword).trim().slice(0, 100),
    page: Number.isSafeInteger(page) && page >= 1 && page <= 2147483647 ? page : 1,
    size: USER_PAGE_SIZES.includes(size) ? size : 20,
    role: USER_ROLE_FILTERS.includes(roleValue) && roleValue !== 'all' ? Number(roleValue) : null,
    status: USER_STATUS_FILTERS.includes(query.status) ? query.status : 'all',
    sortBy: USER_SORTS.includes(query.sortBy) ? query.sortBy : 'id',
    order: query.order === 'asc' ? 'asc' : 'desc',
  }
}

export function usersRouteQuery(state) {
  const query = {}
  if (state.keyword) query.keyword = state.keyword
  if (state.role != null) query.role = String(state.role)
  if (state.status !== 'all') query.status = state.status
  if (state.sortBy !== 'id') query.sortBy = state.sortBy
  if (state.order !== 'desc') query.order = state.order
  if (state.size !== 20) query.size = String(state.size)
  if (state.page !== 1) query.page = String(state.page)
  return query
}

/** 将页面状态转换为后端列表/导出参数：banned=true 表示封禁生效中。 */
export function userListParams(state) {
  return {
    page: state.page,
    size: state.size,
    keyword: state.keyword || undefined,
    role: state.role ?? undefined,
    banned: state.status === 'banned' ? true : state.status === 'active' ? false : undefined,
    sortBy: state.sortBy,
    order: state.order,
  }
}

export function roleLabel(role) {
  const value = Number(role)
  if (value === 1) return '学生'
  if (value === 2) return '教师'
  if (value === 3) return '管理员'
  return '未知'
}

export function userStatusMeta(row) {
  if (row?.banActive) return { type: 'danger', label: '封禁中' }
  if (row?.isBanned === true || Number(row?.isBanned) === 1) {
    return { type: 'warning', label: '封禁已过期' }
  }
  return { type: 'success', label: '正常' }
}

export function formatUserTime(value) {
  return typeof value === 'string' && value ? value.replace('T', ' ').slice(0, 16) : '未记录'
}

export function banSummary(row) {
  if (!row) return ''
  if (row.banActive) {
    const until = row.banExpireTime ? `至 ${formatUserTime(row.banExpireTime)}` : '永久'
    return `${row.banReason?.trim() || '未填写原因'} · ${until}`
  }
  if (row.isBanned === true || Number(row.isBanned) === 1) {
    return `封禁已于 ${formatUserTime(row.banExpireTime)} 到期`
  }
  return ''
}

export function sortFromTable({ prop, order }) {
  if (!order) return { sortBy: 'id', order: 'desc' }
  return {
    sortBy: USER_SORTS.includes(prop) ? prop : 'id',
    order: order === 'ascending' ? 'asc' : 'desc',
  }
}
