/**
 * 写作台的频道定义与提交载荷构造。
 *
 * 频道差异只体现在 type / category / 必填字段上，后端仍按 post 表统一存储。
 * 这里保持纯函数，便于单测覆盖类型映射与校验，不依赖 Vue 或请求层。
 * 注意：公告不在频道内——它走 /admin/announcements/save，且 classify 会改写分类。
 */

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
    hint: '知识文章：支持标题层级、代码块、公式与表格，发布后可在文章广场检索。',
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
  }
}

export const channelByKey = (key) => CHANNELS[key] || CHANNELS.article

/**
 * 从服务端数据判断内容属于哪个频道。
 * type=3 是班级内容，写作台不承接（更新接口不接受 classId，改错会丢归属）。
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
  const title = String(form.title || '').trim()
  const content = String(form.content || '')
  const summary = String(form.summary || '').trim()
  const tags = (form.tags || []).filter(Boolean)

  if (!title) return { error: '标题不能为空' }
  if (title.length > 128) return { error: '标题不能超过 128 个字符' }
  if (!content.trim()) return { error: '正文不能为空' }
  if (summary.length > 256) return { error: '摘要不能超过 256 个字符' }
  if (tags.length > 8) return { error: '标签最多选择 8 个' }

  const category = config.categories.some((item) => item.value === form.category)
    ? form.category
    : config.defaultCategory

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
      privacy: Number(form.privacy) === 1 ? 1 : 0,
      problemId,
      relatedProblemIds
    }
  }
}
