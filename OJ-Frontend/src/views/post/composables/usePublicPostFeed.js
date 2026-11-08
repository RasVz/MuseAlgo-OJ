import { ref } from 'vue'
import { getPostList, getPostTagOptions } from '@/api/post'
import { usePagedPostList } from './usePagedPostList'

export const publicPostCategories = Object.freeze([
  { id: 'all', name: '全部推荐', icon: 'Reading', iconClass: 'icon-all' },
  { id: 'announcement', name: '系统公告', icon: 'Bell', iconClass: 'icon-announcement' },
  { id: 'solution', name: '题解', icon: 'Document', iconClass: 'icon-sol' },
  { id: 'algorithm', name: '算法·理论', icon: 'DataLine', iconClass: 'icon-alg' },
  { id: 'life', name: '生活·游记', icon: 'Sunny', iconClass: 'icon-life' },
  { id: 'leisure', name: '休闲·娱乐', icon: 'Coffee', iconClass: 'icon-game' },
  { id: 'contest', name: '竞赛·复盘', icon: 'Trophy', iconClass: 'icon-contest' }
])

const toNumber = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

// 标签字段在不同来源下可能是 JSON 字符串、逗号分隔串或数组，
  // 统一解析成数组，解析失败再退回逗号切分。
const parseTags = (value) => {
  if (!value) return []
  if (Array.isArray(value)) return value.filter(Boolean)
  if (typeof value !== 'string') return []

  const text = value.trim()
  if (!text) return []
  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) return parsed.filter(Boolean)
  } catch {
    return text.split(',').map((tag) => tag.trim()).filter(Boolean)
  }
  return []
}

const normalizeArticle = (item = {}) => ({
  ...item,
  id: item.id ?? item.postId,
  title: item.title || '',
  summary: item.summary || '',
  authorId: item.authorId,
  authorName: item.authorName || (item.authorId ? `用户${item.authorId}` : '未知用户'),
  authorAvatar: item.authorAvatar || '',
  tags: parseTags(item.tags),
  createTime: item.createTime,
  views: toNumber(item.views),
  likes: toNumber(item.likes),
  comments: toNumber(item.commentCount ?? item.comments),
  type: item.type,
  classId: item.classId
})

const isClassPost = (item = {}) => item.classId !== null && item.classId !== undefined && item.classId !== ''

/**
 * 公共文章广场的数据模块。
 *
 * 页面通过分类、排序、关键词和标签驱动 `load`；模块隐藏接口参数、
 * 服务端数据兼容与文章规范化，保证页面不依赖后端响应细节。
 *
 * 副作用：每次筛选变化都重新请求列表（内部走 usePagedPostList）；
 * 标签选项由 loadTagOptions 单独拉一次，失败走 notify。
 *
 * @param {Object} options
 * @param {(msg: string) => void} options.notify 错误提示回调
 *
 * @returns {Object} feed 分页状态（loading/records/total/currentPage/load）+
 *   筛选状态（currentCategory/sortBy/searchKeyword/selectedTags/tagGroups）+
 *   标签操作（loadTagOptions/applyTags/removeTag/clearTags/filterByTag）
 */
export function usePublicPostFeed({ notify = () => {} } = {}) {
  const currentCategory = ref('all')
  const sortBy = ref('latest')
  const searchKeyword = ref('')
  const selectedTags = ref([])
  const tagGroups = ref([])

  // 分类筛选同时下发给服务端与前端 filterRecord：服务端分页后再过滤会让 total 与实际条数不符，
  // 前端这一层保证"班级内部帖"不会混进公共广场。
  const matchesCategory = (item = {}) => {
    if (isClassPost(item)) return false

    if (currentCategory.value === 'announcement') {
      return toNumber(item.type) === 0 && item.category === 'announcement'
    }
    if (currentCategory.value === 'solution') return toNumber(item.type) === 1
    if (currentCategory.value === 'all') return [0, 1, 2, 3].includes(toNumber(item.type))
    return toNumber(item.type) === 0 && item.category === currentCategory.value
  }

  const buildParams = (page, pageSize) => {
    const params = {
      currentPage: page,
      pageSize,
      keyword: searchKeyword.value || undefined,
      tags: selectedTags.value.length ? selectedTags.value.join(',') : undefined,
      sort: sortBy.value
    }

    if (currentCategory.value === 'announcement') return { ...params, type: 0, category: 'announcement' }
    if (currentCategory.value === 'solution') return { ...params, type: 1 }
    if (currentCategory.value === 'all') return { ...params, types: '0,1,2,3' }
    return { ...params, type: 0, category: currentCategory.value }
  }

  const feed = usePagedPostList({
    pageSize: 10,
    fetchPage: ({ page, pageSize }) => getPostList(buildParams(page, pageSize)),
    normalizeRecord: normalizeArticle,
    filterRecord: matchesCategory,
    onError: (message) => notify(message || '获取文章列表失败，请稍后重试')
  })

  const loadTagOptions = async () => {
    try {
      const response = await getPostTagOptions()
      if (Number(response?.code) === 200) {
        tagGroups.value = Object.entries(response.data || {}).map(([label, options]) => ({ label, options }))
      }
    } catch {
      notify('获取文章标签失败，请稍后重试')
    }
  }

  // 标签筛选后回到第 1 页：保留原页码会落到一个不存在的空页。
  const applyTags = (tags) => {
    selectedTags.value = [...tags]
    return feed.load(1)
  }

  const removeTag = (tag) => applyTags(selectedTags.value.filter((item) => item !== tag))
  const clearTags = () => applyTags([])
  const filterByTag = (tag) => applyTags([tag])

  return {
    ...feed,
    currentCategory,
    sortBy,
    searchKeyword,
    selectedTags,
    tagGroups,
    loadTagOptions,
    applyTags,
    removeTag,
    clearTags,
    filterByTag
  }
}
