import { Table, TableCell, TableHeader, TableRow } from '@tiptap/extension-table'

// 表格对齐是 Markdown 管道表的一部分（:--- / :---: / ---:）。
// Tiptap 默认单元格不存对齐，这里补一个 align 属性，避免保存时静默丢对齐。
const alignAttribute = {
  align: {
    default: null,
    parseHTML: (element) => {
      const value = element.style?.textAlign || element.getAttribute('align') || ''
      return ['left', 'center', 'right'].includes(value) ? value : null
    },
    renderHTML: (attributes) => (attributes.align ? { style: `text-align:${attributes.align}` } : {})
  }
}

const AlignedTableCell = TableCell.extend({
  addAttributes() {
    return { ...this.parent?.(), ...alignAttribute }
  }
})

const AlignedTableHeader = TableHeader.extend({
  addAttributes() {
    return { ...this.parent?.(), ...alignAttribute }
  }
})

export const tableExtensions = [
  Table.configure({ resizable: false, allowTableNodeSelection: true }),
  TableRow,
  AlignedTableHeader,
  AlignedTableCell
]
