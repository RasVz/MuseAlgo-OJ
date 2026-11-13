const PREFIX = 'musealgo:scoped-draft'

const getStorage = () => {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

const safeParse = raw => {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/**
 * 按场次隔离的本地代码草稿。
 *
 * 比赛用 contest:{id}，试卷用 paper:{attemptId}，再叠加题目与语言，
 * 避免练习、比赛、重考互相串码。只存本机，不写服务端。
 */
export function createScopedDraftAdapter(scopeKey) {
  const base = `${PREFIX}:${scopeKey}`
  const keyFor = ({ problemId, language }) => `${base}:${problemId}:${language}`

  return {
    async load({ problemId, language }) {
      const raw = getStorage()?.getItem(keyFor({ problemId, language }))
      return raw ? safeParse(raw) : null
    },
    async latest({ problemId }) {
      const storage = getStorage()
      if (!storage) return null
      const prefix = `${base}:${problemId}:`
      let latest = null
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index)
        if (!key || !key.startsWith(prefix)) continue
        const parsed = safeParse(storage.getItem(key))
        if (!parsed) continue
        if (!latest || (parsed.updatedAt || 0) > (latest.updatedAt || 0)) {
          latest = { ...parsed, language: key.slice(prefix.length) }
        }
      }
      return latest
    },
    async save({ problemId, language, code }) {
      getStorage()?.setItem(
        keyFor({ problemId, language }),
        JSON.stringify({ code, language, updatedAt: Date.now() })
      )
    },
    async clear({ problemId, language }) {
      getStorage()?.removeItem(keyFor({ problemId, language }))
    }
  }
}
