import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import MarkdownIt from 'markdown-it'

// Exercise the component's actual renderer and watcher without a browser.
function render(content) {
  const source = readFileSync(new URL('../../src/views/post/components/PostContent.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
  const context = {
    MarkdownIt,
    markdownItMathjax3() {},
    hljs: { getLanguage: () => false },
    defineProps: () => ({ content }),
    defineEmits: () => () => {},
    ref: value => ({ value }),
    watch: (getter, callback) => callback(getter())
  }
  vm.runInNewContext(source + '\nglobalThis.output = htmlContent.value', context)
  return context.output
}

test('stored post raw HTML is escaped before reaching v-html', () => {
  const html = render('<img src=x onerror="window.syntheticMarker=1">\n<script>window.syntheticMarker=1</script>')
  assert.doesNotMatch(html, /<img\b|<script\b/i)
  assert.match(html, /&lt;img/)
  assert.match(html, /&lt;script/)
})

test('unsafe Markdown link schemes do not produce executable links', () => {
  const html = render('[click](javascript:syntheticMarker)\n![image](data:text/html,syntheticMarker)')
  assert.doesNotMatch(html, /(?:href|src)=["'](?:javascript|data):/i)
})

test('normal Markdown headings links images and code still render', () => {
  const html = render('# Title\n\n[site](https://example.test)\n\n![alt](https://example.test/a.png)\n\n```text\n<img>\n```')
  assert.match(html, /<h1 id="heading-0">Title<\/h1>/)
  assert.match(html, /href="https:\/\/example.test"/)
  assert.match(html, /<img src="https:\/\/example.test\/a.png"/)
  assert.match(html, /&lt;img&gt;/)
})
