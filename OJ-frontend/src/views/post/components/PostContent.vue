<template>
  <div class="markdown-body" v-html="htmlContent" ref="contentRef"></div>
</template>

<script setup>
// 文章正文渲染：Markdown → HTML（含代码高亮与公式），并向目录组件上报标题。
// 这里直接用 markdown-it 而非 utils/markdown 工厂，是因为正文需要
// 从渲染结果里抽出 h1/h2/h3 生成锚点目录，而不只是输出 HTML 字符串。
import { ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from '@/utils/highlight'
import 'highlight.js/styles/github.css'
import 'github-markdown-css/github-markdown.css'
import markdownItMathjax3 from 'markdown-it-mathjax3'

const props = defineProps({
  content: { type: String, default: '' }
})

const emit = defineEmits(['toc-updated'])

const htmlContent = ref('')
const contentRef = ref(null)

// 初始化 Markdown 解析器
const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
  highlight: (str, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(str, { language: lang }).value
      } catch { /* 渲染降级：忽略 */ }
    }
    return ''
  }
})
md.use(markdownItMathjax3) // 启用 Latex 支持

// 解析 Markdown 并生成 TOC
const renderAndExtractToc = (markdown) => {
  let rendered = md.render(markdown)
  const toc = []
  
  // 使用正则提取 h1-h3 标签，并注入 id
  // 注意：这里为了解耦，我们在渲染后的 HTML 字符串上做处理，或者在 md.render 之前预处理
  // 下面是一种简单的预处理注入 ID 的方式：
  
  const lines = markdown.split('\n')
  let isInCodeBlock = false
  
  lines.forEach((line, index) => {
    // 简单跳过代码块
    if (line.trim().startsWith('```')) isInCodeBlock = !isInCodeBlock
    if (isInCodeBlock) return

    // 标题锚点必须按行号生成而不是标题文本：
// 同一篇文章里出现两个同名小节（"思路" "复杂度"各写两遍）非常常见，
// 用文本做 id 会重复，目录点击就会跳错位置。
    const match = line.match(/^(#{1,3})\s+(.*)/)
    if (match) {
      const level = match[1].length
      const text = match[2].trim()
      const id = `heading-${index}`
      toc.push({ id, text, level })
    }
  })

  // 给渲染结果里的标题补上与目录一致的 id。之所以按"去标签后的文本依次比对"，
  // 是因为 markdown-it 的渲染顺序与源文本顺序一致；改成真正的 markdown-it 插件
  // 会更稳，但会引入 slug 冲突与转义处理，得不偿失。
  let tocIndex = 0
  rendered = rendered.replace(/<(h[1-3])>(.*?)<\/\1>/g, (match, tag, content) => {
    if (tocIndex < toc.length && toc[tocIndex].text === content.replace(/<[^>]+>/g, '').trim()) {
      const id = toc[tocIndex].id
      tocIndex++
      return `<${tag} id="${id}">${content}</${tag}>`
    }
    return match
  })

  htmlContent.value = rendered
  emit('toc-updated', toc)
}

// immediate 保证首次渲染就能生成目录；空内容不渲染，
// 否则会把空字符串当成一篇文章，目录侧栏出现一个空的"目录"标题。
watch(() => props.content, (newVal) => {
  if (newVal) renderAndExtractToc(newVal)
}, { immediate: true })
</script>

<style scoped>
.markdown-body {
  box-sizing: border-box;
  min-width: 200px;
  max-width: 100%;
  margin: 0 auto;
  padding: 0;
  background: transparent;
}
</style>