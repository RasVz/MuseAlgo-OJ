import { ref, h } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatProblems, previewFormatProblem, applyFormatProblem } from '@/api/problem'

/**
 * 题库管理——AI 格式化逻辑（AdminProblemView / AdminProblemEditView 共用）。
 *
 * @param {Object} deps
 * @param {import('vue').Ref<Array>} deps.selectedProblems 表格当前选中行（批量格式化用）
 * @param {() => Promise<void>} deps.onChanged 格式化生效后刷新列表的回调
 */
export function useProblemFormat({ selectedProblems, onChanged }) {
  const formatting = ref(false)
  const formatResultVisible = ref(false)
  const formatResult = ref(null)
  const formatPreviewVisible = ref(false)
  const formatPreview = ref(null)
  const applyingFormat = ref(false)

  // 批量格式化确认弹窗内的 IP 改名开关状态
  const batchIpMode = ref(false)

  // 确认框用 h() 手写 VNode 而非 HTML 字符串：题面内容会回显到弹窗里，
    // 拼接字符串容易被当成 HTML 解析导致注入与标签错乱。
  const handleFormatSelected = async () => {
    const ids = selectedProblems.value.map(item => item.id).filter(Boolean)
    if (!ids.length) return

    batchIpMode.value = false
    try {
      await ElMessageBox.confirm(
        h('div', { style: 'line-height: 1.8' }, [
          h('p', { style: 'margin: 0 0 10px; color: var(--text-regular); font-size: 13px' },
            `将用 AI 格式化 ${ids.length} 道题目：整理题面结构、抽取时限/内存/输入输出格式/提示/全部样例并翻译成中文。` +
            '格式化后会覆盖这些文本，不修改判题测试数据。'),
          h('p', { style: 'margin: 0 0 12px; color: var(--text-secondary); font-size: 12px' },
            `每道题一次 AI 调用，${ids.length} 道可能需要数分钟，请耐心等待。`),
          h('div', { style: 'display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: var(--bg-surface-soft); border-radius: 8px; border: 1px solid var(--border-light)' }, [
            h('span', { style: 'font-size: 13px; color: var(--text-primary)' }, 'IP 改名'),
            h('el-switch', {
              modelValue: batchIpMode.value,
              'onUpdate:modelValue': (v) => { batchIpMode.value = v }
            }),
            h('span', { style: 'font-size: 12px; color: var(--text-secondary)' }, '按睿思星球风格替换题面命名实体')
          ])
        ]),
        '确认批量格式化',
        { type: 'warning', confirmButtonText: '开始格式化', cancelButtonText: '取消', dangerouslyUseHTMLString: false }
      )
    } catch {
      return
    }

    formatting.value = true
    try {
      const res = await formatProblems(ids, batchIpMode.value)
      if (res.code !== 200) {
        ElMessage.error(res.message || '题目格式化失败')
        return
      }
      formatResult.value = res.data
      formatResultVisible.value = true
      selectedProblems.value = []
      await onChanged()
    } catch (error) {
      ElMessage.error(error?.response?.data?.message || error?.message || '题目格式化失败')
    } finally {
      formatting.value = false
    }
  }

  /**
   * 单题格式化（先确认再调用，一次 AI 调用完成格式化与可选 IP 改名）。
   *
   * @param {Object} row 题目行（需含 id）
   * @param {boolean} ipMode 是否启用睿思星球 IP 改名（与格式化同一次调用完成）
   */
  // 预览先占位再覆盖：AI 调用可能耗时数分钟，先展示"正在整理"占位，
  // 让弹窗立即出现，避免用户以为点击没生效。
  const handleFormatOne = async (row, ipMode = false) => {
    try {
      await ElMessageBox.confirm(
        `将用 AI 格式化该题：整理题面结构、抽取时限/内存/输入输出格式/提示/全部样例并翻译成中文，`
        + (ipMode ? '并按睿思星球风格替换题面命名实体（IP 改名）。' : '。')
        + '预览确认后才会保存，未应用前不会改动原题。',
        '确认 AI 格式化',
        { type: 'warning', confirmButtonText: '开始格式化', cancelButtonText: '取消' }
      )
    } catch {
      return
    }

    formatPreviewVisible.value = true
    formatPreview.value = { problemId: row.id, success: false, message: '正在调用 AI 整理...' }
    try {
      const res = await previewFormatProblem(row.id, ipMode)
      if (res.code !== 200) {
        formatPreview.value = { problemId: row.id, success: false, message: res.message || '格式化失败' }
        return
      }
      formatPreview.value = res.data
    } catch (error) {
      formatPreview.value = {
        problemId: row.id,
        success: false,
        message: error?.response?.data?.message || error?.message || '格式化失败'
      }
    }
  }

  // 应用格式化必须校验 preview.success 与 formatted 同时存在：
    // 前者排除调用失败时的占位对象，后者排除 AI 未产出内容的情况，避免把空题面写回。
  const handleApplyFormat = async () => {
    if (!formatPreview.value?.success || !formatPreview.value?.formatted) return
    applyingFormat.value = true
    try {
      const res = await applyFormatProblem(formatPreview.value.problemId, formatPreview.value.formatted)
      if (res.code !== 200) {
        ElMessage.error(res.message || '保存失败')
        return
      }
      ElMessage.success('格式化结果已保存')
      formatPreviewVisible.value = false
      await onChanged()
    } catch (error) {
      ElMessage.error(error?.response?.data?.message || error?.message || '保存失败')
    } finally {
      applyingFormat.value = false
    }
  }

  return {
    formatting, formatResultVisible, formatResult,
    formatPreviewVisible, formatPreview, applyingFormat,
    handleFormatSelected, handleFormatOne, handleApplyFormat
  }
}
