import MarkdownIt from 'markdown-it'

export const INTRODUCTION_LIMIT = 20000
export const PROFILE_IMAGE_LIMIT = 5 * 1024 * 1024

export function safeProfileImageUrl(value) {
  if (
    typeof value !== 'string' ||
    /[\s\\]/.test(value) ||
    [...value].some((char) => char.charCodeAt(0) < 32)
  )
    return ''
  if (value.startsWith('/uploads/') && !value.includes('..')) return value
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? value : ''
  } catch {
    return ''
  }
}

export function validateProfileImage(file) {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type))
    return '请选择 JPG、PNG 或 WebP 图片'
  if (file.size > PROFILE_IMAGE_LIMIT) return '图片不能超过 5MB'
  return ''
}

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
const renderImage = md.renderer.rules.image
md.renderer.rules.image = (tokens, index, options, env, renderer) => {
  const token = tokens[index]
  if (!safeProfileImageUrl(token.attrGet('src')))
    return md.utils.escapeHtml(token.content || '图片地址无效')
  token.attrSet('loading', 'lazy')
  token.attrSet('decoding', 'async')
  return renderImage(tokens, index, options, env, renderer)
}
md.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return renderer.renderToken(tokens, index, options)
}

export function renderProfileMarkdown(source = '') {
  return md.render(String(source))
}

export function insertProfileImage(source, url, start, end = start) {
  if (!safeProfileImageUrl(url)) throw new Error('上传返回的图片地址无效')
  const snippet = `\n![图片](<${url}>)\n`
  return {
    text: source.slice(0, start) + snippet + source.slice(end),
    cursor: start + snippet.length,
  }
}
