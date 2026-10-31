import request from '@/utils/request'

function normalizeType(rawType) {
  if (rawType === undefined || rawType === null || rawType === '') {
    return 2 // 默认题单类型为数字 2
  }

  // 数字类型（例如 2）
  if (typeof rawType === 'number') {
    return rawType
  }

  const typeStr = String(rawType).trim()
  const lower = typeStr.toLowerCase()

  const typeMap = {
    problem: 1,
    problem_list: 2,
    problemlist: 2,
    contest: 3,
    post: 4
  }

  if (typeMap[lower] !== undefined) return typeMap[lower]

  return rawType
}

function buildPayload(input = {}) {
  const targetId = input.targetId ?? input.id
  // 调用方可能传 type 或 targetType，统一归一为后端约定的 targetType
  const targetType = normalizeType(input.type ?? input.targetType)
  return { targetId, targetType }
}

/**
 * 检查当前用户是否收藏了某个目标
 * 兼容传参：{ targetId, type } 或 { targetId, targetType }
 */
export function checkFavorite(params) {
  return request({
    url: '/favorite/check',
    method: 'get',
    params: buildPayload(params)
  })
}

// 获取收藏的题单列表（VO 版本）
export function getFavoriteProblemLists(params) {
  return request({
    url: '/favorite/problem_list/vo/list',
    method: 'get',
    params
  })
}

// 移除收藏记录
export function removeFavorite(data) {
  return request({
    url: '/favorite/remove',
    method: 'post',
    data: buildPayload(data)
  })
}

// 新增收藏记录
export function addFavorite(data) {
  return request({
    url: '/favorite/add',
    method: 'post',
    data: buildPayload(data)
  })
}

// 获取收藏的帖子列表（type: 0-动态, 1-题解/文章）
export function getFavoritePosts(type = 0) {
  return request({
    url: '/favorite/posts/list',
    method: 'get',
    params: { type }
  })
}
