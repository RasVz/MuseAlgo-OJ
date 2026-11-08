const URL_SCHEME = /^[a-z][a-z\d+.-]*:/i
const HTTP_URL = /^https?:\/\//i
const RELATIVE_URL_BASE = 'https://courseware.invalid'

/**
 * Return a normalized safe courseware URL, or null for unsupported input.
 * Absolute HTTP(S) links support external providers; relative links stay on this
 * origin and are rooted at the application path for compatibility with stored URLs.
 */
export function normalizeCoursewareUrl(value) {
  if (typeof value !== 'string') return null

  const candidate = value.trim()
  if (!candidate || candidate.includes('\\')) return null
  if ([...candidate].some(character => character.charCodeAt(0) < 0x20 || character.charCodeAt(0) === 0x7f)) return null

  if (HTTP_URL.test(candidate)) {
    try {
      const parsed = new URL(candidate)
      if (!['http:', 'https:'].includes(parsed.protocol) || !parsed.hostname) return null
      return parsed.href
    } catch {
      return null
    }
  }

  // Network-path references and non-web schemes can otherwise escape the app origin.
  if (candidate.startsWith('//') || URL_SCHEME.test(candidate)) return null

  const candidatePath = candidate.split(/[?#]/, 1)[0]
  if (!candidatePath) return null
  const relativePath = candidate.startsWith('/') ? candidate : `/${candidate}`

  try {
    const parsed = new URL(relativePath, RELATIVE_URL_BASE)
    if (parsed.origin !== RELATIVE_URL_BASE || !['http:', 'https:'].includes(parsed.protocol)) return null
    return `${parsed.pathname}${parsed.search}${parsed.hash}`
  } catch {
    return null
  }
}
