import test from 'node:test'
import assert from 'node:assert/strict'

import {
  checkRoundTrip,
  docToMarkdown,
  markdownToDoc,
  renderMathHtml
} from '../../src/utils/markdown-blocks.js'

const roundTrip = (markdown) => markdownToDoc(docToMarkdown(markdownToDoc(markdown)))

test('标题层级与内联标记保持稳定', () => {
  const doc = markdownToDoc('# 一级标题\n\n正文 **加粗** 与 *斜体* 和 `code`')
  assert.equal(doc.content[0].type, 'heading')
  assert.equal(doc.content[0].attrs.level, 1)
  assert.equal(doc.content[1].type, 'paragraph')
  assert.deepEqual(roundTrip('# 一级标题\n\n正文 **加粗** 与 *斜体* 和 `code`'), doc)
})

test('无序列表与有序列表往返一致', () => {
  assert.deepEqual(roundTrip('- 第一项\n- 第二项'), markdownToDoc('- 第一项\n- 第二项'))

  const ordered = markdownToDoc('3. 甲\n4. 乙')
  assert.equal(ordered.content[0].type, 'orderedList')
  assert.equal(ordered.content[0].attrs.start, 3)
  assert.equal(docToMarkdown(ordered), '3. 甲\n4. 乙')
})

test('引用内的多段落不合并', () => {
  const source = '> 第一段\n>\n> 第二段'
  const doc = markdownToDoc(source)
  assert.equal(doc.content[0].type, 'blockquote')
  assert.equal(doc.content[0].content.length, 2)
  assert.deepEqual(roundTrip(source), doc)
})

test('代码块保留语言与缩进', () => {
  const source = '```js\nconst a = 1\n  return a\n```'
  const doc = markdownToDoc(source)
  assert.equal(doc.content[0].type, 'codeBlock')
  assert.equal(doc.content[0].attrs.language, 'js')
  assert.equal(docToMarkdown(doc), source)
})

test('分割线与图片可往返', () => {
  const source = '---\n\n![示意图](https://example.com/a.png)'
  const doc = markdownToDoc(source)
  assert.equal(doc.content[0].type, 'horizontalRule')
  assert.equal(doc.content[1].content[0].type, 'image')
  assert.deepEqual(roundTrip(source), doc)
})

test('链接与行内代码不被转义破坏', () => {
  const source = '参见 [文档](https://example.com/docs) 与 `npm run build`'
  assert.deepEqual(roundTrip(source), markdownToDoc(source))
})

test('普通文本中的 Markdown 符号会转义且可还原', () => {
  const source = '计算 a*b 时不要写成 1. 项，也不要写 # 号开头'
  const doc = markdownToDoc(source)
  const markdown = docToMarkdown(doc)
  assert.match(markdown, /a\\\*b/)
  assert.deepEqual(markdownToDoc(markdown), doc)
})

test('表格往返保留表头与单元格', () => {
  const source = '| 名称 | 值 |\n| --- | --- |\n| 时间 | O(n) |'
  const doc = markdownToDoc(source)
  assert.equal(doc.content[0].type, 'table')
  assert.equal(doc.content[0].content.length, 2)
  assert.equal(doc.content[0].content[0].content[0].type, 'tableHeader')
  assert.equal(docToMarkdown(doc), source)
})

test('表格对齐标记往返保留', () => {
  const source = '| 左 | 中 | 右 |\n| :--- | :---: | ---: |\n| a | b | c |'
  const doc = markdownToDoc(source)
  assert.deepEqual(doc.content[0].content[0].content.map((cell) => cell.attrs.align), ['left', 'center', 'right'])
  assert.equal(docToMarkdown(doc), source)
})

test('往返校验对合法文档返回 null', () => {
  const doc = markdownToDoc('# 标题\n\n- 甲\n- 乙\n\n```py\nprint(1)\n```')
  assert.equal(checkRoundTrip(doc), null)
})

test('尾部空段落不视为结构变化', () => {
  const doc = {
    type: 'doc',
    content: [
      { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '小节' }] },
      { type: 'paragraph', content: [{ type: 'text', text: '正文' }] },
      { type: 'paragraph' }
    ]
  }
  assert.equal(checkRoundTrip(doc), null)
})

test('行内公式被解析为独立节点且可往返', () => {
  const source = '质能方程 $E=mc^2$ 很简洁'
  const doc = markdownToDoc(source)
  const types = doc.content[0].content.map((node) => node.type)
  assert.deepEqual(types, ['text', 'mathInline', 'text'])
  assert.equal(doc.content[0].content[1].attrs.latex, 'E=mc^2')
  assert.equal(docToMarkdown(doc), source)
  assert.deepEqual(roundTrip(source), doc)
})

test('块级公式保留原文并可往返', () => {
  const source = '$$\n\\int_0^1 x^2 dx\n$$'
  const doc = markdownToDoc(source)
  assert.equal(doc.content[0].type, 'mathBlock')
  assert.equal(doc.content[0].attrs.latex, '\\int_0^1 x^2 dx')
  assert.equal(docToMarkdown(doc), source)
})

test('公式渲染使用 MathJax 输出', () => {
  assert.match(renderMathHtml('x^2'), /mjx-container/)
})

test('正文里的美元符号被转义，不会误判为公式', () => {
  const source = '价格 $5 与 $10 的差别'
  const doc = markdownToDoc(source)
  const markdown = docToMarkdown(doc)
  assert.match(markdown, /\\\$5/)
  assert.deepEqual(markdownToDoc(markdown), doc)
})
