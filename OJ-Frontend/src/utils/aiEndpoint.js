// Preview only. The backend resolves and validates the final request target again.
export function resolveAiEndpoint(input, addressType = 'AUTO') {
  try {
    if (!['AUTO', 'BASE_URL', 'FULL_URL'].includes(addressType)) return ''
    const url = new URL(String(input || '').trim())
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname ||
        url.username || url.password || url.search || url.hash) return ''
    const trimmed = url.pathname.replace(/\/+$/, '')
    const complete = trimmed.endsWith('/chat/completions')
    const base = !trimmed || /\/v[0-9]+$/.test(trimmed)
    if (addressType !== 'FULL_URL' && !complete && (addressType === 'BASE_URL' || base)) {
      url.pathname = `${trimmed}/chat/completions`
    }
    return url.toString()
  } catch {
    return ''
  }
}
