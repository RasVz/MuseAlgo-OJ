/** 请求代际隔离独立于视图，防止关闭、切题和清空后的迟到响应污染对话。 */
export function createTutorConversation({ load, clear, send, onChange, onError, onMetadata = () => {} }) {
  let generation = 0
  let controller = null
  let state = { key: '', problemId: null, messages: [], text: '', busy: false, loading: false, clearing: false, ready: false, lastQuestion: '', error: '' }
  const update = patch => { state = { ...state, ...patch }; onChange(state) }
  const stop = () => {
    generation++
    controller?.abort()
    const messages = state.text ? [...state.messages, { role: 'ai', content: state.text + '\n\n> 已停止生成，回复未完成。' }] : state.messages
    update({ busy: false, loading: false, clearing: false, text: '', messages })
  }
  return {
    get state() { return state },
    stop,
    async open(key, problemId) {
      if (state.key === key && state.ready) return
      stop()
      const version = ++generation
      controller = new AbortController()
      const signal = controller.signal
      update({ key, problemId, messages: [], loading: true, ready: false, error: '', lastQuestion: '' })
      try {
        const messages = await load(problemId, signal)
        if (version !== generation) return
        update({ messages: messages.map(message => ({ role: message.role === 'assistant' ? 'ai' : 'user', content: message.content })), ready: true })
      } catch (error) {
        if (version !== generation || signal.aborted) return
        update({ error: error.message || '对话恢复失败，请重试' }); onError(error)
      } finally { if (version === generation) update({ loading: false }) }
    },
    async ask(payload) {
      if (state.busy || state.loading || state.clearing || !state.ready) return false
      const version = ++generation
      controller = new AbortController()
      const signal = controller.signal
      update({ busy: true, error: '', text: '', lastQuestion: payload.studentQuestion,
        messages: [...state.messages, { role: 'user', content: payload.studentQuestion }] })
      try {
        const answer = await send(payload, signal, text => { if (version === generation) update({ text }) }, (name, data) => { if (version === generation && !signal.aborted) onMetadata(name, data, payload) })
        if (version !== generation) return false
        if (!answer.trim()) throw new Error('助教没有返回内容，请重试')
        update({ messages: [...state.messages, { role: 'ai', content: answer }], text: '' })
        return true
      } catch (error) {
        if (version !== generation || signal.aborted) return false
        const messages = state.text ? [...state.messages, { role: 'ai', content: state.text + '\n\n> 回复未完成，请重试。' }] : state.messages
        update({ messages, text: '', error: error.message || 'AI 请求失败，请重试' }); onError(error)
        return false
      } finally { if (version === generation) update({ busy: false }) }
    },
    async clear() {
      if (state.loading || state.clearing) return false
      stop()
      const version = ++generation
      update({ clearing: true, error: '' })
      try {
        await clear(state.problemId)
        if (version !== generation) return false
        update({ messages: [], lastQuestion: '', ready: true })
        return true
      } catch (error) {
        if (version !== generation) return false
        update({ error: error.message || '清空失败，请重试' }); onError(error)
        return false
      } finally { if (version === generation) update({ clearing: false }) }
    }
  }
}
