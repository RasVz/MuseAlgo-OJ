import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import MathNodeView from '../components/MathNodeView.vue'

// 行内公式：对应 Markdown 的 $...$，与详情页 markdown-it-mathjax3 的语义一致。
export const MathInline = Node.create({
  name: 'mathInline',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  draggable: false,
  addAttributes() {
    return {
      latex: {
        default: '',
        parseHTML: (element) => element.getAttribute('data-latex') || '',
        renderHTML: (attributes) => ({ 'data-latex': attributes.latex || '' })
      }
    }
  },
  parseHTML() {
    return [{ tag: 'span[data-math-inline]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes({ 'data-math-inline': '' }, HTMLAttributes)]
  },
  addNodeView() {
    return VueNodeViewRenderer(MathNodeView)
  }
})

// 块级公式：对应 Markdown 的 $$...$$。
export const MathBlock = Node.create({
  name: 'mathBlock',
  group: 'block',
  atom: true,
  selectable: true,
  draggable: false,
  addAttributes() {
    return {
      latex: {
        default: '',
        parseHTML: (element) => element.getAttribute('data-latex') || '',
        renderHTML: (attributes) => ({ 'data-latex': attributes.latex || '' })
      }
    }
  },
  parseHTML() {
    return [{ tag: 'div[data-math-block]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes({ 'data-math-block': '' }, HTMLAttributes)]
  },
  addNodeView() {
    return VueNodeViewRenderer(MathNodeView)
  }
})
