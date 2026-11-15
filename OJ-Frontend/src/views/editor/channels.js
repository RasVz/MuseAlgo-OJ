/**
 * 写作台的频道定义与提交载荷构造。
 *
 * 频道差异只体现在 type / category / 必填字段上，后端仍按 post 表统一存储。
 * 这里保持纯函数，便于单测覆盖类型映射与校验，不依赖 Vue 或请求层。
 * 注意：公告不在频道内——它走 /admin/announcements/save，且 classify 会改写分类。
 */
import { parsePostImages } from '../../utils/post-images.js'

export const ARTICLE_CATEGORIES = [
  { value: 'algorithm', label: '算法·理论' },
  { value: 'life', label: '生活·游记' },
  { value: 'leisure', label: '休闲·娱乐' },
  { value: 'contest', label: '竞赛·复盘' }
]

export const DISCUSSION_CATEGORIES = [
  { value: 'question', label: '提问' },
  { value: 'experience', label: '经验分享' },
  { value: 'contest-discussion', label: '比赛讨论' }
]

export const CHANNELS = {
  article: {
    key: 'article',
    label: '文章',
    type: 2,
    defaultCategory: 'algorithm',
    categories: ARTICLE_CATEGORIES,
    hint: '知识文章：支持标题层级、代码块、公式与表格，发布后可在社区检索。',
    placeholder: '写一篇知识文章…支持 Markdown、LaTeX 与代码块'
  },
  discussion: {
    key: 'discussion',
    label: '讨论',
    type: 0,
    defaultCategory: 'question',
    categories: DISCUSSION_CATEGORIES,
    hint: '讨论贴：适合提问、经验分享与比赛讨论，默认进入讨论广场。',
    placeholder: '想讨论什么？把问题背景和已尝试的思路写清楚'
  },
  solution: {
    key: 'solution',
    label: '题解',
    type: 1,
    defaultCategory: 'solution',
    categories: [{ value: 'solution', label: '题解' }],
    hint: '题解：需关联题目；建议按「思路 / 复杂度 / 代码」分节书写。',
    placeholder: '写题解…建议按「思路 / 复杂度 / 代码」分节'
  },
  // 班级动态只从班级页进入，不在公共写作台露出；
  // 类型、分类、可见性都由班级归属锁定，不提供发布设置。
  class: {
    key: 'class',
    label: '班级动态',
    type: 3,
    defaultCategory: 'question',
    categories: [{ value: 'question', label: '班级动态' }],
    hidden: true,
    requiresClass: true,
    hint: '班级动态：发布后只在本班可见，支持标题、列表、引用、代码块、公式与表格。',
    placeholder: '分享给班级同学…支持 Markdown、LaTeX 与代码块'
  }
}

export const channelByKey = (key) => CHANNELS[key] || CHANNELS.article

/**
 * 从服务端数据判断内容属于哪个频道。
 * type=3 是班级内容，只在带 classId 的隐藏频道里编辑，避免改错归属。
 */
export function resolveChannel(type, category) {
  const value = Number(type)
  if (value === 3) return 'class'
  if (value === 1 || category === 'solution') return 'solution'
  if (value === 2) return 'article'
  // 历史数据里存在 type=0 但分类属于文章的文章，按分类优先归到文章。
  if (ARTICLE_CATEGORIES.some((item) => item.value === category)) return 'article'
  return 'discussion'
}

/**
 * 构造 /post/add 与 /post/update 的请求体。
 * @returns {{payload?: Object, error?: string}}
 */
export function buildPayload({ channel, form }) {
  const config = channelByKey(channel)
  const isClass = config.key === 'class'
  const title = String(form.title || '').trim()
  const content = String(form.content || '')
  const summary = String(form.summary || '').trim()
  // 班级动态不需要标签与题目关联：它们只服务于公共检索与社区推荐。
  const tags = isClass ? [] : (form.tags || []).filter(Boolean)

  if (!title) return { error: '标题不能为空' }
  if (title.length > 128) return { error: '标题不能超过 128 个字符' }
  if (!content.trim()) return { error: '正文不能为空' }
  if (summary.length > 256) return { error: '摘要不能超过 256 个字符' }
  if (tags.length > 8) return { error: '标签最多选择 8 个' }

  let classId = null
  if (isClass) {
    classId = Number(form.classId)
    if (!Number.isSafeInteger(classId) || classId <= 0) {
      return { error: '缺少班级信息，请从班级页重新进入' }
    }
  }

  const category = isClass
    ? 'question'
    : (config.categories.some((item) => item.value === form.category)
      ? form.category
      : config.defaultCategory)

  let problemId = null
  if (config.key === 'solution') {
    problemId = Number(form.problemId)
    if (!Number.isSafeInteger(problemId) || problemId <= 0) return { error: '题解必须关联题目' }
  }

  const relatedProblemIds = config.key === 'article'
    ? (form.relatedProblemIds || []).filter((id) => Number.isSafeInteger(Number(id))).slice(0, 12).map(Number)
    : []

  return {
    payload: {
      id: form.id ?? null,
      title,
      content,
      summary,
      type: config.type,
      category,
      tags: tags.join(','),
      privacy: isClass ? 0 : (Number(form.privacy) === 1 ? 1 : 0),
      problemId,
      relatedProblemIds,
      // 正文仍是内容源；images 只供列表缩略图，最多保留 9 条。
      images: parsePostImages(form.images).slice(0, 9).join(','),
      ...(isClass ? { classId } : {})
    }
  }
}
