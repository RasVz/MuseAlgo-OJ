// 题面 Markdown 的前端兜底消毒：与后端 StatementSanitizer 同一策略。
//
// 为什么需要两份：后端在写入时消毒（新数据），历史数据仍可能带着旧的富文本；
// 题面最终以 v-html 呈现，读侧再按白名单过滤一次，历史行也不会执行脚本。
// 为什么不是禁用 html: true：FPS 导入的题面本身就是 HTML，禁用会把整段题面变成转义文本。
//
// 策略（与后端逐条对应）：
// - 围栏代码块、行内代码原样保留：Markdown 渲染器会转义其内容，不存在执行面；
// - 白名单标签只保留安全属性，URL 只允许 http/https/mailto/ftp 与相对路径；
// - script/style/iframe/svg 等危险标签连同内容删除；
// - 未知标签只有携带事件属性或可执行 URL 时才删除，避免破坏 <iostream> 之类的正文。

const MAX_TAG_LENGTH = 4096

const ALLOWED_ATTRIBUTES = {
  a: ['href', 'title'],
  blockquote: ['cite'],
  col: ['span', 'width'],
  colgroup: ['span', 'width'],
  del: ['cite', 'datetime'],
  img: ['align', 'alt', 'height', 'src', 'title', 'width'],
  ins: ['cite', 'datetime'],
  ol: ['start', 'type'],
  q: ['cite'],
  table: ['summary', 'width'],
  td: ['abbr', 'axis', 'colspan', 'rowspan', 'width'],
  th: ['abbr', 'axis', 'colspan', 'rowspan', 'scope', 'width'],
  ul: ['type']
}

const ALLOWED_TAGS = new Set([
  'a', 'b', 'blockquote', 'br', 'caption', 'cite', 'code', 'col', 'colgroup',
  'dd', 'div', 'dl', 'dt', 'em', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'i',
  'img', 'li', 'ol', 'p', 'pre', 'q', 'small', 'span', 'strike', 'strong',
  'sub', 'sup', 'table', 'tbody', 'td', 'tfoot', 'th', 'thead', 'tr', 'u', 'ul'
])

const DROP_WITH_CONTENT = new Set([
  'script', 'style', 'iframe', 'object', 'embed', 'applet', 'frame', 'frameset',
  'svg', 'math', 'noscript', 'template', 'textarea', 'title', 'xmp', 'plaintext'
])

const DROP_TAG_ONLY = new Set([
  'meta', 'link', 'base', 'form', 'input', 'button', 'select', 'option', 'optgroup',
  'video', 'audio', 'source', 'track'
])

const ACTIVE_ATTRIBUTES = new Set([
  'href', 'src', 'srcset', 'xlink:href', 'srcdoc', 'formaction', 'action',
  'poster', 'data', 'style', 'background', 'dynsrc', 'lowsrc'
])

const URL_ATTRIBUTES = new Set(['href', 'cite', 'src'])
const URL_PROTOCOLS = {
  href: ['http', 'https', 'mailto', 'ftp'],
  cite: ['http', 'https'],
  src: ['http', 'https']
}

const ATTRIBUTE_TEXT = /^\s*(?:[A-Za-z_:][-A-Za-z0-9_:.]*(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?\s*)*\/?\s*$/
const ATTRIBUTE_PAIR = /([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g
const FENCE_OPEN = /^ {0,3}(`{3,}|~{3,}).*$/

function escapeAttribute(value) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function isSafeUrl(tag, name, value) {
  // 去掉空白与控制字符，避免 "java\tscript:" 这类绕过
  const compact = Array.from(String(value))
    .filter(ch => ch.charCodeAt(0) > 0x20)
    .join('')
  const colon = compact.indexOf(':')
  const slash = compact.search(/[/?#]/)
  if (colon < 0 || (slash >= 0 && slash < colon)) return true
  const scheme = compact.slice(0, colon).toLowerCase()
  return (URL_PROTOCOLS[name] || []).includes(scheme)
}

function filterAttributes(tag, attributeText) {
  const allowed = ALLOWED_ATTRIBUTES[tag] || []
  const kept = []
  ATTRIBUTE_PAIR.lastIndex = 0
  let match
  while ((match = ATTRIBUTE_PAIR.exec(attributeText)) !== null) {
    const name = match[1].toLowerCase()
    const value = match[2] ?? match[3] ?? match[4] ?? ''
    if (!allowed.includes(name)) continue
    if (URL_ATTRIBUTES.has(name) && !isSafeUrl(tag, name, value)) continue
    kept.push(`${name}="${escapeAttribute(value)}"`)
  }
  return kept.length ? ` ${kept.join(' ')}` : ''
}

function hasActiveAttributes(attributeText) {
  ATTRIBUTE_PAIR.lastIndex = 0
  let match
  while ((match = ATTRIBUTE_PAIR.exec(attributeText)) !== null) {
    const name = match[1].toLowerCase()
    if (name.startsWith('on') || ACTIVE_ATTRIBUTES.has(name)) return true
  }
  return false
}

function skipElement(source, from, name) {
  const lower = source.toLowerCase()
  const index = lower.indexOf(`</${name}`, from)
  if (index < 0) return from
  const close = source.indexOf('>', index)
  return close < 0 ? source.length : close + 1
}

// '<' 是否可能开启一个标签：是则必须转义，避免浏览器按标签解析
function looksLikeTagStart(source, lt) {
  const next = source[lt + 1]
  if (!next) return false
  if (next === '/' || next === '!' || next === '?') return true
  return /[A-Za-z]/.test(next)
}

// 返回 start 处的标签；不是可识别标签时返回 null（调用方按普通文本处理）
function parseTag(source, start) {
  const length = source.length
  if (source.startsWith('<!--', start)) {
    const commentEnd = source.indexOf('-->', start + 4)
    const end = commentEnd < 0 ? length : commentEnd + 3
    return { name: '', closing: false, raw: source.slice(start, end), attributeText: '', end }
  }
  let index = start + 1
  if (index >= length) return null
  const first = source[index]
  if (first === '!' || first === '?') {
    const end = source.indexOf('>', index)
    if (end < 0 || end - start > MAX_TAG_LENGTH) return null
    return { name: '', closing: false, raw: source.slice(start, end + 1), attributeText: '', end: end + 1 }
  }
  let closing = false
  if (first === '/') {
    closing = true
    index++
  }
  const nameStart = index
  while (index < length && /[A-Za-z0-9:-]/.test(source[index])) index++
  if (index === nameStart) return null
  if (!/[A-Za-z]/.test(source[nameStart])) return null

  let close = index
  let quote = ''
  while (close < length) {
    const current = source[close]
    if (quote) {
      if (current === quote) quote = ''
    } else if (current === '"' || current === "'") {
      quote = current
    } else if (current === '>') {
      break
    } else if (close - start > MAX_TAG_LENGTH) {
      return null
    }
    close++
  }
  if (close >= length) return null

  const attributeText = source.slice(index, close)
  if (!ATTRIBUTE_TEXT.test(attributeText)) return null
  if (closing && attributeText.trim()) return null
  return {
    name: source.slice(nameStart, index).toLowerCase(),
    closing,
    raw: source.slice(start, close + 1),
    attributeText,
    end: close + 1
  }
}

function isFenceClose(line, fenceChar, fenceLength) {
  let index = 0
  while (index < line.length && index < 3 && line[index] === ' ') index++
  const start = index
  while (index < line.length && line[index] === fenceChar) index++
  if (index - start < fenceLength) return false
  return !line.slice(index).trim()
}

function collectInlineCode(line, offset, ranges) {
  let index = 0
  while (index < line.length) {
    if (line[index] !== '`') {
      index++
      continue
    }
    const runStart = index
    while (index < line.length && line[index] === '`') index++
    const runLength = index - runStart
    let search = index
    let closeEnd = -1
    while (search < line.length) {
      if (line[search] !== '`') {
        search++
        continue
      }
      const closeStart = search
      while (search < line.length && line[search] === '`') search++
      if (search - closeStart === runLength) {
        closeEnd = search
        break
      }
    }
    if (closeEnd > 0) {
      ranges.push([offset + runStart, offset + closeEnd])
      index = closeEnd
    }
  }
}

function protectedRanges(source) {
  const ranges = []
  let pos = 0
  while (pos <= source.length) {
    let lineEnd = source.indexOf('\n', pos)
    if (lineEnd < 0) lineEnd = source.length
    const line = source.slice(pos, lineEnd)
    const fence = FENCE_OPEN.exec(line)
    if (fence) {
      const marker = fence[1]
      const fenceChar = marker[0]
      const fenceLength = marker.length
      let scan = lineEnd + 1
      let blockEnd = source.length
      while (scan < source.length) {
        let end = source.indexOf('\n', scan)
        if (end < 0) end = source.length
        if (isFenceClose(source.slice(scan, end), fenceChar, fenceLength)) {
          blockEnd = end
          break
        }
        scan = end + 1
      }
      ranges.push([pos, blockEnd])
      pos = blockEnd + 1
      continue
    }
    collectInlineCode(line, pos, ranges)
    pos = lineEnd + 1
  }
  return ranges
}

/**
 * 按白名单消毒题面 Markdown/HTML 源码，返回可供 markdown-it（html: true）渲染的文本。
 * 只处理标签，正文与 Markdown 语法保持原样。
 */
export function sanitizeStatementMarkup(source) {
  if (typeof source !== 'string' || !source || !source.includes('<')) return source || ''
  const ranges = protectedRanges(source)
  let out = ''
  let cursor = 0
  let rangeIndex = 0
  while (cursor < source.length) {
    const lt = source.indexOf('<', cursor)
    if (lt < 0) {
      out += source.slice(cursor)
      break
    }
    while (rangeIndex < ranges.length && ranges[rangeIndex][1] <= lt) rangeIndex++
    if (rangeIndex < ranges.length && ranges[rangeIndex][0] <= lt && lt < ranges[rangeIndex][1]) {
      const end = Math.min(ranges[rangeIndex][1], source.length)
      out += source.slice(cursor, end)
      cursor = end
      continue
    }
    out += source.slice(cursor, lt)
    const tag = parseTag(source, lt)
    if (!tag) {
      // 疑似标签但语法无法确认：转义 '<'，否则浏览器仍可能按标签解析（如 <img/src=x onerror=1>）
      out += looksLikeTagStart(source, lt) ? '&lt;' : '<'
      cursor = lt + 1
      continue
    }
    if (!tag.name) {
      cursor = tag.end
      continue
    }
    if (tag.closing) {
      if (ALLOWED_TAGS.has(tag.name)) out += `</${tag.name}>`
      cursor = tag.end
      continue
    }
    if (DROP_WITH_CONTENT.has(tag.name)) {
      cursor = skipElement(source, tag.end, tag.name)
      continue
    }
    if (DROP_TAG_ONLY.has(tag.name)) {
      cursor = tag.end
      continue
    }
    if (ALLOWED_TAGS.has(tag.name)) {
      out += `<${tag.name}${filterAttributes(tag.name, tag.attributeText)}>`
    } else if (!hasActiveAttributes(tag.attributeText)) {
      out += tag.raw
    }
    cursor = tag.end
  }
  return out
}
