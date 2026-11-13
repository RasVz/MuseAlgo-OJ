import MarkdownIt from 'markdown-it'
import markdownItMathjax3 from 'markdown-it-mathjax3'

/**
 * Markdown 与 Tiptap 块文档之间的双向转换。
 *
 * 方案 B 以块为编辑单位，但 post.content 仍存 Markdown：
 * 进入编辑器时把 Markdown 解析为块，保存时把块序列化回 Markdown。
 * 因此这里是唯一需要与详情页渲染规则对齐的地方。
 *
 * 不支持的结构不静默丢弃：解析时收集并抛 UnsupportedMarkdownError，
 * 由调用方决定降级（提示或退回旧编辑器），避免用户原文被悄悄改写。
 */

const md = new MarkdownIt({ html: false, linkify: false, typographer: false })
// 与详情页同一个数学插件：解析出的公式语义必须一致，否则往返会改写用户公式。
md.use(markdownItMathjax3)

export class UnsupportedMarkdownError extends Error {
  constructor(constructs) {
    super(`暂不支持的 Markdown 结构：${constructs.join('、')}`)
    this.name = 'UnsupportedMarkdownError'
    this.unsupported = constructs
  }
}

const MARK_PRIORITY = { code: 0, bold: 1, italic: 2, strike: 3, link: 4 }

const parseAlign = (style) => {
  const match = String(style || '').match(/text-align:\s*(left|center|right)/)
  return match ? match[1] : null
}

const textNode = (text, marks = []) => ({
  type: 'text',
  text,
  ...(marks.length ? { marks: uniqueMarks(marks) } : {})
})

function uniqueMarks(marks) {
  const seen = new Set()
  return marks.filter((mark) => {
    const key = typeof mark === 'string' ? mark : `${mark.type}:${mark.attrs?.href || ''}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function parseInline(children = [], unsupported = []) {
  const nodes = []
  const marks = []

  const push = (node) => {
    if (node.type === 'text' && node.text === '') return
    nodes.push(node)
  }

  for (const token of children) {
    switch (token.type) {
      case 'text':
        push(textNode(token.content, marks.slice()))
        break
      case 'strong_open':
        marks.push('bold')
        break
      case 'em_open':
        marks.push('italic')
        break
      case 's_open':
        marks.push('strike')
        break
      case 'strong_close':
      case 'em_close':
      case 's_close':
        marks.pop()
        break
      case 'code_inline':
        push(textNode(token.content, [...marks, 'code']))
        break
      case 'link_open': {
        const href = token.attrGet('href') || ''
        marks.push({ type: 'link', attrs: { href, ...(token.attrGet('title') ? { title: token.attrGet('title') } : {}) } })
        break
      }
      case 'link_close':
        marks.pop()
        break
      case 'image':
        push({
          type: 'image',
          attrs: {
            src: token.attrGet('src') || '',
            alt: token.content || token.attrGet('alt') || null,
            title: token.attrGet('title') || null
          }
        })
        break
      case 'math_inline':
        push({ type: 'mathInline', attrs: { latex: token.content.trim() } })
        break
      case 'softbreak':
        push(textNode(' ', marks.slice()))
        break
      case 'hardbreak':
        push({ type: 'hardBreak' })
        break
      case 'html_inline':
        unsupported.push('内联 HTML')
        push(textNode(token.content, marks.slice()))
        break
      default:
        if (token.content) push(textNode(token.content, marks.slice()))
    }
  }

  return nodes
}

function parseBlocks(tokens, start, stopType, unsupported) {
  const nodes = []
  let i = start

  const parseList = (listToken, ordered) => {
    const listNode = {
      type: ordered ? 'orderedList' : 'bulletList',
      ...(ordered && Number(listToken.attrGet('start') || 1) !== 1
        ? { attrs: { start: Number(listToken.attrGet('start')) } }
        : {}),
      content: []
    }
    i += 1
    while (i < tokens.length && tokens[i].type === 'list_item_open') {
      i += 1
      const item = { type: 'listItem', content: [] }
      const inner = parseBlocks(tokens, i, 'list_item_close', unsupported)
      item.content = inner.nodes
      i = inner.next
      listNode.content.push(item)
    }
    if (tokens[i]?.type === (ordered ? 'ordered_list_close' : 'bullet_list_close')) i += 1
    return listNode
  }

  while (i < tokens.length) {
    const token = tokens[i]
    if (stopType && token.type === stopType) return { nodes, next: i + 1 }

    if (token.type === 'heading_open') {
      const level = Number(token.tag.slice(1))
      const inline = tokens[i + 1]
      const content = parseInline(inline?.children, unsupported)
      nodes.push({ type: 'heading', attrs: { level }, ...(content.length ? { content } : {}) })
      i += 3
      continue
    }

    if (token.type === 'paragraph_open') {
      const inline = tokens[i + 1]
      const content = parseInline(inline?.children, unsupported)
      nodes.push({ type: 'paragraph', ...(content.length ? { content } : {}) })
      i += 3
      continue
    }

    if (token.type === 'bullet_list_open') {
      nodes.push(parseList(token, false))
      continue
    }

    if (token.type === 'ordered_list_open') {
      nodes.push(parseList(token, true))
      continue
    }

    if (token.type === 'blockquote_open') {
      i += 1
      const inner = parseBlocks(tokens, i, 'blockquote_close', unsupported)
      i = inner.next
      nodes.push({ type: 'blockquote', content: inner.nodes })
      continue
    }

    if (token.type === 'fence' || token.type === 'code_block') {
      const language = token.type === 'fence' ? (token.info.trim().split(/\s+/)[0] || null) : null
      nodes.push({
        type: 'codeBlock',
        ...(language ? { attrs: { language } } : {}),
        ...(token.content ? { content: [{ type: 'text', text: token.content.replace(/\n$/, '') }] } : {})
      })
      i += 1
      continue
    }

    if (token.type === 'hr') {
      nodes.push({ type: 'horizontalRule' })
      i += 1
      continue
    }

    if (token.type === 'math_block') {
      nodes.push({ type: 'mathBlock', attrs: { latex: token.content.trim() } })
      i += 1
      continue
    }

    if (token.type === 'table_open') {
      i += 1
      const rows = []
      while (i < tokens.length && tokens[i].type !== 'table_close') {
        const current = tokens[i]
        if (current.type === 'tr_open') {
          i += 1
          const cells = []
          while (i < tokens.length && tokens[i].type !== 'tr_close') {
            const cellToken = tokens[i]
            if (cellToken.type === 'th_open' || cellToken.type === 'td_open') {
              const align = parseAlign(cellToken.attrGet('style'))
              const inlineContent = parseInline(tokens[i + 1]?.children, unsupported)
              cells.push({
                type: cellToken.type === 'th_open' ? 'tableHeader' : 'tableCell',
                attrs: { colspan: 1, rowspan: 1, colwidth: null, ...(align ? { align } : {}) },
                content: [{ type: 'paragraph', ...(inlineContent.length ? { content: inlineContent } : {}) }]
              })
              i += 3
              continue
            }
            i += 1
          }
          if (tokens[i]?.type === 'tr_close') i += 1
          rows.push({ type: 'tableRow', content: cells })
          continue
        }
        i += 1
      }
      if (tokens[i]?.type === 'table_close') i += 1
      nodes.push({ type: 'table', content: rows })
      continue
    }

    i += 1
  }

  return { nodes, next: i }
}

/**
 * Markdown → Tiptap 文档。
 * @param {string} markdown
 * @returns {{type:'doc', content:Array}}
 */
export function markdownToDoc(markdown = '') {
  const source = String(markdown || '').replace(/\r\n/g, '\n')
  if (!source.trim()) return { type: 'doc', content: [{ type: 'paragraph' }] }

  const unsupported = []
  const tokens = md.parse(source, {})
  const { nodes } = parseBlocks(tokens, 0, null, unsupported)
  if (unsupported.length) throw new UnsupportedMarkdownError([...new Set(unsupported)])

  return { type: 'doc', content: nodes.length ? nodes : [{ type: 'paragraph' }] }
}

const escapeInline = (text) => String(text).replace(/([\\`*_[\]$])/g, '\\$1')

const escapeLineStart = (text) => {
  if (/^\s*[#>+-]\s/.test(text)) return text.replace(/^(\s*)([#>+-])/, '$1\\$2')
  if (/^\s*\d+[.)]\s/.test(text)) return text.replace(/^(\s*\d+)([.)])/, '$1\\$2')
  return text
}

function codeSpan(text) {
  const value = String(text)
  if (!value.includes('`')) return `\`${value}\``
  return `\`\` ${value} \`\``
}

function serializeInline(nodes = []) {
  return nodes
    .map((node) => {
      if (node.type === 'hardBreak') return '  \n'
      if (node.type === 'image') {
        const { src = '', alt = '', title = '' } = node.attrs || {}
        return `![${alt || ''}](${src}${title ? ` "${title}"` : ''})`
      }
      if (node.type === 'mathInline') return `$${node.attrs?.latex || ''}$`
      if (node.type !== 'text') return node.text || ''

      let value = String(node.text || '')
      const marks = [...(node.marks || [])].sort(
        (a, b) => (MARK_PRIORITY[typeof a === 'string' ? a : a.type] ?? 99) -
          (MARK_PRIORITY[typeof b === 'string' ? b : b.type] ?? 99)
      )
      // 代码段内不转义：反斜杠在 code span 里是字面量，转义反而会污染内容。
      const hasCode = marks.some((mark) => (typeof mark === 'string' ? mark : mark.type) === 'code')
      if (!hasCode) value = escapeInline(value)

      for (const mark of marks) {
        const type = typeof mark === 'string' ? mark : mark.type
        if (type === 'code') value = codeSpan(value)
        else if (type === 'bold') value = `**${value}**`
        else if (type === 'italic') value = `*${value}*`
        else if (type === 'strike') value = `~~${value}~~`
        else if (type === 'link') {
          const href = mark.attrs?.href || ''
          const title = mark.attrs?.title
          value = `[${value}](${href}${title ? ` "${title}"` : ''})`
        }
      }
      return value
    })
    .join('')
}

function indentLines(text, indent) {
  return text
    .split('\n')
    .map((line) => (line ? indent + line : line))
    .join('\n')
}

function serializeListItem(item) {
  const blocks = (item.content || []).map(serializeNode).filter(Boolean)
  if (!blocks.length) return ''
  const [first, ...rest] = blocks
  return [first, ...rest.map((block) => indentLines(block, '  '))].join('\n')
}

const escapePipes = (text) => String(text).replace(/\|/g, '\\|')

const alignMarker = (align) => {
  if (align === 'center') return ':---:'
  if (align === 'right') return '---:'
  if (align === 'left') return ':---'
  return '---'
}

// 单元格只承载行内内容；多段落/列表会被压平成一行，往返校验会拦下这类结构。
const cellText = (cell) => (cell.content || [])
  .map((block) => serializeNode(block))
  .filter(Boolean)
  .join(' ')
  .replace(/\s*\n+\s*/g, ' ')

function serializeTable(node) {
  const rows = node.content || []
  if (!rows.length) return ''
  const grid = rows.map((row) => (row.content || []).map((cell) => ({
    text: escapePipes(cellText(cell)),
    align: cell.attrs?.align || null
  })))
  const columns = Math.max(...grid.map((row) => row.length), 1)
  const pad = (row) => Array.from({ length: columns }, (_, index) => row[index] || { text: '', align: null })
  const line = (row) => `| ${pad(row).map((cell) => cell.text).join(' | ')} |`
  const header = pad(grid[0])
  const separator = `| ${header.map((cell) => alignMarker(cell.align)).join(' | ')} |`
  return [line(grid[0]), separator, ...grid.slice(1).map(line)].join('\n')
}

function serializeNode(node) {
  switch (node.type) {
    case 'paragraph':
      return escapeLineStart(serializeInline(node.content))
    case 'heading': {
      const level = Math.min(Math.max(Number(node.attrs?.level || 1), 1), 6)
      return `${'#'.repeat(level)} ${serializeInline(node.content)}`
    }
    case 'bulletList':
      return (node.content || [])
        .map((item) => `- ${serializeListItem(item)}`)
        .join('\n')
    case 'orderedList': {
      const start = Number(node.attrs?.start || 1)
      return (node.content || [])
        .map((item, index) => `${start + index}. ${serializeListItem(item)}`)
        .join('\n')
    }
    case 'blockquote': {
      const inner = (node.content || []).map(serializeNode).filter(Boolean).join('\n\n')
      return inner
        .split('\n')
        .map((line) => (line ? `> ${line}` : '>'))
        .join('\n')
    }
    case 'codeBlock': {
      const language = node.attrs?.language || ''
      const code = (node.content || []).map((child) => child.text || '').join('')
      return `\`\`\`${language}\n${code}\n\`\`\``
    }
    case 'horizontalRule':
      return '---'
    case 'table':
      return serializeTable(node)
    case 'mathInline':
      return `$${node.attrs?.latex || ''}$`
    case 'mathBlock':
      return `$$\n${node.attrs?.latex || ''}\n$$`
    case 'image': {
      const { src = '', alt = '', title = '' } = node.attrs || {}
      return `![${alt || ''}](${src}${title ? ` "${title}"` : ''})`
    }
    default:
      return serializeInline(node.content)
  }
}

/**
 * Tiptap 文档 → Markdown。
 * @param {{type:'doc', content:Array}} doc
 * @returns {string}
 */
export function docToMarkdown(doc = { type: 'doc', content: [] }) {
  const blocks = (doc.content || []).map(serializeNode).filter((block) => block !== '')
  return blocks.join('\n\n').replace(/\n{3,}/g, '\n\n').trimEnd()
}

/**
 * 往返校验：序列化后再解析，比较结构与文本。
 * 返回 null 表示通过，否则返回差异描述。
 */
const isTrailingEmptyParagraph = (node) => node?.type === 'paragraph' && !(node.content || []).length

const normalizeMark = (mark) => {
  if (typeof mark === 'string') return mark
  const attrs = Object.fromEntries(Object.entries(mark.attrs || {}).filter(([, value]) => value !== null && value !== undefined))
  return { type: mark.type, ...(Object.keys(attrs).length ? { attrs } : {}) }
}

/**
 * 比较前的归一化：Tiptap 会为尾部补一个空段落（TrailingNode），
 * 而 Markdown 无法表达也不需要有；属性里的 null 默认值同样不代表内容差异。
 * 这里只抹平"表示层"差异，节点类型、顺序与文本内容仍严格比较。
 */
const normalizeNode = (node) => {
  const result = { type: node.type }
  const attrs = Object.fromEntries(
    Object.entries(node.attrs || {}).filter(([, value]) => value !== null && value !== undefined)
  )
  if (Object.keys(attrs).length) result.attrs = attrs
  if (node.marks?.length) result.marks = node.marks.map(normalizeMark)
  if (node.text !== undefined) result.text = node.text
  if (node.content) result.content = node.content.map(normalizeNode)
  return result
}

function normalizeDoc(doc = { type: 'doc', content: [] }) {
  const content = [...(doc.content || [])]
  while (content.length && isTrailingEmptyParagraph(content[content.length - 1])) content.pop()
  return { type: 'doc', content: content.map(normalizeNode) }
}

export function checkRoundTrip(doc) {
  const markdown = docToMarkdown(doc)
  try {
    const reparsed = markdownToDoc(markdown)
    if (JSON.stringify(normalizeDoc(reparsed)) !== JSON.stringify(normalizeDoc(doc))) {
      return { markdown, reason: '结构在序列化后发生变化' }
    }
    return null
  } catch (error) {
    if (error instanceof UnsupportedMarkdownError) {
      return { markdown, reason: error.message }
    }
    throw error
  }
}

/**
 * 单个公式的渲染：复用与详情页相同的 markdown-it + MathJax 管线，
 * 保证编辑器里看到的公式与发布后一致（不引入 KaTeX）。
 * @param {string} latex
 * @param {boolean} displayMode 块级公式
 * @returns {string} HTML 字符串
 */
export function renderMathHtml(latex, displayMode = false) {
  const source = String(latex || '')
  if (!source.trim()) return ''
  if (displayMode) return md.render(`$$\n${source}\n$$`)
  // 行内公式外层会被包一层 <p>，节点视图里需要裸片段。
  return md.render(`$${source}$`).replace(/^<p>/, '').replace(/<\/p>\s*$/, '')
}
