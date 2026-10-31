import MarkdownIt from 'markdown-it'
import hljs from '@/utils/highlight'
import 'highlight.js/styles/github.css'
import 'github-markdown-css/github-markdown.css'

// 不需要公式的页面可直接使用此入口，避免加载 MathJax。
export function createBasicMarkdown({ html = true, breaks = false, highlight = true } = {}) {
  const md = new MarkdownIt({
    html,
    linkify: true,
    typographer: true,
    breaks,
    ...(highlight
      ? {
          highlight: (code, language) => {
            if (language && hljs.getLanguage(language)) {
              try {
                return `<pre class="hljs"><code>${hljs.highlight(code, { language }).value}</code></pre>`
              } catch (_) {
                /* 高亮失败降级为纯文本转义 */
              }
            }
            return `<pre class="hljs"><code>${md.utils.escapeHtml(code)}</code></pre>`
          }
        }
      : {})
  })
  return md
}
