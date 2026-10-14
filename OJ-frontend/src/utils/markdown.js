import MarkdownIt from 'markdown-it'
import markdownItMathjax3 from 'markdown-it-mathjax3'
import hljs from '@/utils/highlight'
import 'highlight.js/styles/github.css'
import 'github-markdown-css/github-markdown.css'

/**
 * 全站统一的 Markdown 渲染工厂。
 *
 * 历史上 PostEditorView / PostContent / ProblemDescription / AiAssistant
 * 各自 new MarkdownIt，配置漂移（html 开关、数学公式、代码高亮不一致）。
 * 统一收敛到这里，新增渲染需求只允许改工厂参数，不再各自实例化。
 *
 * @param {Object} options
 * @param {boolean} [options.html=true]  是否放行原始 HTML（编辑器本地预览必须传 false，防 XSS）
 * @param {boolean} [options.math=true]  是否启用 LaTeX（mathjax3 插件）
 * @param {boolean} [options.breaks=false] 换行符转 <br>（聊天气泡场景用）
 * @param {boolean} [options.highlight=true] 是否启用 highlight.js 代码高亮
 */
export function createMarkdown({ html = true, math = true, breaks = false, highlight = true } = {}) {
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
  if (math) {
    md.use(markdownItMathjax3)
  }
  return md
}
