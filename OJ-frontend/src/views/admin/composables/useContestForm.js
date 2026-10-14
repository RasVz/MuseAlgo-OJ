import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { getContestDetail, createContest, updateContest } from '@/api/contest'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

/**
 * 比赛编辑器——基本信息表单逻辑（从 ContestEditorView 抽离）。
 *
 * 职责：路由派生状态、表单数据、校验、详情加载、创建/保存。
 * 导航跳转（创建成功进编辑页等）由调用方根据 saveContest 返回值决定。
 */
export function useContestForm() {
  const route = useRoute()

  const formRef = ref(null)
  const loadingDetail = ref(false)
  const saving = ref(false)

  const dateRange = ref([])
  const form = reactive({
    id: null,
    title: '',
    description: '',
    startTime: null,
    endTime: null,
    mode: 'ACM',
    visible: 1
  })
  // 编辑模式下"取消修改"要回到打开页面时的值，所以额外存一份 detail 快照；
// 创建模式没有快照，只能回到空白表单。
  const formSnapshot = ref(null)

  const rules = {
    title: [{ required: true, message: '请输入比赛名称', trigger: 'blur' }]
  }

  // 全局比赛与班级比赛复用同一套表单，仅靠路由名区分；
    // classId 既可能来自 query 也可能来自 params，两处都要兜。
  const isCreateMode = computed(() => route.name === 'AdminContestCreate' || route.name === 'AdminClassContestCreate')
  const isEditMode = computed(() => route.name === 'AdminContestEdit' || route.name === 'AdminClassContestEdit')
  const contestId = computed(() => Number(route.params.id))
  const classId = computed(() => {
    const value = route.query.classId ?? route.params.classId
    const num = Number(value)
    return Number.isFinite(num) && num > 0 ? num : null
  })
  const isClassMode = computed(() => !!classId.value)

  const pageTitle = computed(() => {
    if (isClassMode.value) {
      return isCreateMode.value ? '新建班级比赛' : `编辑班级比赛 #${contestId.value}`
    }
    return isCreateMode.value ? '新建比赛' : `编辑比赛 #${contestId.value}`
  })

  const pageDesc = computed(() => {
    if (isClassMode.value) {
      return isCreateMode.value
        ? `为班级 #${classId.value} 创建一个新比赛`
        : `管理班级 #${classId.value} 的当前比赛基本信息`
    }
    return isCreateMode.value ? '创建一个新比赛' : '管理当前比赛（基本信息 / 题目管理）'
  })

  // 时间范围选择器是数组，表单提交需要 startTime/endTime 两个字段，
    // 统一在这里双向同步；清空选择器时必须把两个字段都置 null，否则会提交上次的时间。
  watch(dateRange, (val) => {
    if (val && val.length === 2) {
      form.startTime = val[0]
      form.endTime = val[1]
    } else {
      form.startTime = null
      form.endTime = null
    }
  })

  function getDefaultForm() {
    return {
      id: null,
      title: '',
      description: '',
      startTime: null,
      endTime: null,
      visible: 1
    }
  }

  function applyDefaultForm() {
    Object.assign(form, getDefaultForm())
    dateRange.value = []
  }

  /** 页面级重置：表单 + 快照（题目列表清空由 useContestProblems 负责） */
  function resetPageState() {
    applyDefaultForm()
    formSnapshot.value = null
  }

  function resetForm() {
    if (isEditMode.value && formSnapshot.value) {
      Object.assign(form, { ...formSnapshot.value })
      if (form.startTime && form.endTime) {
        dateRange.value = [form.startTime, form.endTime]
      } else {
        dateRange.value = []
      }
      return
    }
    applyDefaultForm()
  }

  function fillFormFromDetail(detail) {
    const normalized = {
      id: detail?.id ?? detail?.contestId ?? null,
      title: detail?.title ?? detail?.contestName ?? '',
      description: detail?.description ?? '',
      startTime: detail?.startTime ?? detail?.beginTime ?? null,
      endTime: detail?.endTime ?? detail?.finishTime ?? null,
      mode: detail?.mode || 'ACM',
      visible: Number(detail?.visible ?? 1) === 0 ? 0 : 1
    }

    Object.assign(form, normalized)
    formSnapshot.value = { ...normalized }

    if (form.startTime && form.endTime) {
      dateRange.value = [form.startTime, form.endTime]
    } else {
      dateRange.value = []
    }
  }

  async function loadContestDetail() {
    loadingDetail.value = true
    try {
      const res = await getContestDetail(contestId.value)
      if (!isResultSuccess(res)) {
        ElMessage.error(getResultMessage(res, '获取比赛详情失败'))
        return
      }
      const detail = unwrapResultData(res) || {}
      fillFormFromDetail(detail)
    } catch (e) {
      ElMessage.error(e?.message || '获取比赛详情失败')
    } finally {
      loadingDetail.value = false
    }
  }

  // 提交前兜底校验：Element Plus 的 rules 只挡了标题，
    // 时间与可见性若只靠前端控件约束，接口层拿到的脏数据无法追溯，这里再挡一次。
  function validateForm() {
    if (!form.title) {
      ElMessage.warning('请输入比赛名称')
      return false
    }
    if (!form.startTime || !form.endTime) {
      ElMessage.warning('请选择比赛时间范围')
      return false
    }
    if (dayjs(form.endTime).isBefore(dayjs(form.startTime))) {
      ElMessage.warning('结束时间不能早于开始时间')
      return false
    }
    if (![0, 1].includes(Number(form.visible))) {
      ElMessage.warning('可见性参数不合法')
      return false
    }
    return true
  }

  // 创建接口返回的新比赛 ID 位置不固定（数字、字符串、对象都可能），
    // 统一在这里取出；拿不到就退化为"创建成功但不跳转"。
  function tryExtractContestId(res) {
    const data = unwrapResultData(res)
    if (typeof data === 'number') return data
    if (typeof data === 'string' && /^\d+$/.test(data)) return Number(data)
    if (data && typeof data === 'object') {
      if (data.id != null) return Number(data.id)
      if (data.contestId != null) return Number(data.contestId)
    }
    return null
  }

  /**
   * 创建或保存比赛。
   * @returns {Promise<{ok: boolean, createdId?: number}>}
   *   ok=true 且 createdId 存在 → 创建成功并拿到 ID（调用方应跳转编辑页）；
   *   ok=true 无 createdId → 创建成功但无 ID（调用方返回列表）或保存成功（无需动作）。
   */
  async function saveContest() {
    if (!validateForm()) return { ok: false }

    saving.value = true
    try {
      const payload = {
        id: isEditMode.value ? contestId.value : undefined,
        contestId: isEditMode.value ? contestId.value : undefined,
        title: form.title,
        contestName: form.title,
        description: form.description,
        startTime: form.startTime,
        endTime: form.endTime,
        mode: form.mode,
        visible: Number(form.visible),
        classId: classId.value ?? undefined
      }

      if (isCreateMode.value) {
        const res = await createContest(payload)
        if (!isResultSuccess(res)) {
          ElMessage.error(getResultMessage(res, '创建失败'))
          return { ok: false }
        }
        const newId = tryExtractContestId(res.data.id)
        if (newId) {
          ElMessage.success('创建成功，已进入编辑模式')
          return { ok: true, createdId: newId }
        }
        ElMessage.success('创建成功')
        return { ok: true }
      }

      const res = await updateContest(payload)
      if (!isResultSuccess(res)) {
        ElMessage.error(getResultMessage(res, '保存失败'))
        return { ok: false }
      }
      ElMessage.success('保存成功')
      await loadContestDetail()
      return { ok: true }
    } catch (e) {
      ElMessage.error(e?.message || '保存失败')
      return { ok: false }
    } finally {
      saving.value = false
    }
  }

  return {
    // state
    formRef, form, dateRange, rules, formSnapshot,
    loadingDetail, saving,
    // derived
    isCreateMode, isEditMode, contestId, classId, isClassMode, pageTitle, pageDesc,
    // actions
    resetPageState, resetForm, loadContestDetail, validateForm, saveContest
  }
}
