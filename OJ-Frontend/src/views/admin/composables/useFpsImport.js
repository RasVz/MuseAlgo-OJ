import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { importFpsProblemSet } from '@/api/problem'

/**
 * 题库管理——FPS 题集导入逻辑（从 AdminProblemView 抽离）。
 *
 * @param {Object} deps
 * @param {() => Promise<void>} deps.onImported 导入成功（succeeded>0）后刷新列表的回调
 */
export function useFpsImport({ onImported }) {
  const fpsFileInput = ref(null)
  const importing = ref(false)
  const importResultVisible = ref(false)
  const importResult = ref(null)

  const chooseFpsFile = () => {
    fpsFileInput.value?.click()
  }

  // 选完立刻清空 input.value：否则连续选同一个文件时 change 不再触发，导入看起来"点了没反应"。
  const handleFpsFileSelected = async (event) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return

    const formData = new FormData()
    formData.append('file', file)
    importing.value = true
    try {
      const res = await importFpsProblemSet(formData)
      if (res.code !== 200) {
        ElMessage.error(res.message || 'FPS 导入失败')
        return
      }
      importResult.value = res.data
      importResultVisible.value = true
      if (res.data?.succeeded > 0) {
        await onImported()
      }
    } catch (error) {
      ElMessage.error(error?.response?.data?.message || error?.message || 'FPS 导入失败')
    } finally {
      importing.value = false
    }
  }

  return {
    fpsFileInput, importing, importResultVisible, importResult,
    chooseFpsFile, handleFpsFileSelected
  }
}
