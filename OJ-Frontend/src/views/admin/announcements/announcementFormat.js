/**
 * 公告列表展示用的纯格式化函数。
 * 后端 LocalDateTime 可能序列化为 "2026-11-08 09:30:00" 或 ISO 带 T 的字符串，
 * 两种都要稳定显示为同一格式；解析失败时退回原文截断，不抛错。
 */
import dayjs from 'dayjs'

export function formatAnnouncementTime(value) {
  if (!value) return '-'
  const text = String(value)
  const normalized = text.includes('T') ? text : text.replace(' ', 'T')
  const parsed = dayjs(normalized)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : text.slice(0, 16)
}

/**
 * 把 Markdown 正文压成单行摘要，避免列表里出现标题符号与原始链接语法。
 * 结果只用于 {{ }} 插值，不需要额外转义。
 */
export function announcementExcerpt(content, limit = 80) {
  const plain = String(content || '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[`*_>#~|-]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return plain.length > limit ? `${plain.slice(0, limit)}…` : plain
}
