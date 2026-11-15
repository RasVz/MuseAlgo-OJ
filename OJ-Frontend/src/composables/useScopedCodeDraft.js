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
 * 按用户与场次隔离的本地代码草稿。
 *
 * 用户后再按 contest:{id} 或 paper:{attemptId}、题目与语言区分，
 * 避免练习、比赛、重考互相串码。只存本机，不写服务端。
 */
export function createScopedDraftAdapter(scopeKey, userId) {
  const base = userId == null ? null : `${PREFIX}:user:${userId}:${scopeKey}`
  const keyFor = ({ problemId, language }) => `${base}:${problemId}:${language}`

  return {
    async load({ problemId, language }) {
      if (!base) return null
      const raw = getStorage()?.getItem(keyFor({ problemId, language }))
      return raw ? safeParse(raw) : null
    },
    async latest({ problemId }) {
      const storage = getStorage()
      if (!base || !storage) return null
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
      if (!base) return
      getStorage()?.setItem(
        keyFor({ problemId, language }),
        JSON.stringify({ code, language, updatedAt: Date.now() })
      )
    },
    async clear({ problemId, language }) {
      if (!base) return
      getStorage()?.removeItem(keyFor({ problemId, language }))
    }
  }
}
