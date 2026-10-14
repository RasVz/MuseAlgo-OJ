import { ref } from 'vue'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

/**
 * 分页帖子列表的状态模块。
 *
 * 调用方只提供"如何取一页数据"的 adapter（fetchPage）；本模块统一处理
 * 不同分页响应形状、加载状态、页码与失败时的清空行为。
 *
 * @param {Function} fetchPage (page:number,pageSize:number) => Promise<后端分页响应>
 * @param {number} pageSize 每页条数，默认 10
 * @param {Function} normalizeRecord 单条记录 -> 前端卡片模型的映射
 * @param {Function} filterRecord 前端二次过滤（如隐藏已删除项）
 * @param {Function} onError (message, error) => void 失败回调，由调用方决定是否弹 toast
 *
 * @returns {Object} 响应式的 loading / records / total / currentPage / pageSize，
 *   以及 load(page) 与 clear()；load 在成功与失败时都会把 loading 复位。
 */
export function usePagedPostList({
  fetchPage,
  pageSize = 10,
  normalizeRecord = (record) => record,
  filterRecord = () => true,
  onError = () => {}
}) {
  const loading = ref(false)
  const records = ref([])
  const total = ref(0)
  const currentPage = ref(1)

  const getRecords = (data) => {
    if (Array.isArray(data)) return data
    if (Array.isArray(data?.records)) return data.records
    if (Array.isArray(data?.list)) return data.list
    return []
  }

  const getTotal = (data, fallback) => {
    const value = Number(data?.total)
    return Number.isFinite(value) ? value : fallback
  }

  const clear = () => {
    records.value = []
    total.value = 0
  }

  // 任何失败都先清空列表：保留上一批数据会让用户误以为筛选条件没生效。
  // 具体要不要弹提示交给调用方的 onError，避免各处重复写 toast。
  const load = async (page = 1) => {
    loading.value = true
    try {
      const response = await fetchPage({ page, pageSize })
      if (!isResultSuccess(response)) {
        clear()
        onError(getResultMessage(response, '获取列表失败'))
        return false
      }

      const data = unwrapResultData(response)
      const pageRecords = getRecords(data)
        .map(normalizeRecord)
        .filter(filterRecord)

      records.value = pageRecords
      total.value = getTotal(data, pageRecords.length)
      currentPage.value = page
      return true
    } catch (error) {
      clear()
      onError(error?.message || '获取列表失败', error)
      return false
    } finally {
      loading.value = false
    }
  }

  return {
    loading,
    records,
    total,
    currentPage,
    pageSize,
    load,
    clear
  }
}
