import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { getContestDetail, createContest, updateContest } from '@/api/contest'
import { getContestLevelList } from '@/api/adminContestLevel'
import { useUserStore } from '@/stores/userStore'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

/**
 * 比赛编辑器——基本信息表单逻辑（从 ContestEditorView 抽离）。
 *
 * 职责：路由派生状态、表单数据、校验、详情加载、等级下拉、创建/保存。
 * 导航跳转（创建成功进编辑页等）由调用方根据 saveContest 返回值决定。
 *
 * T10-1：新增比赛分类（正式 FORMAL / 娱乐 ENTERTAINMENT）、比赛等级（Rating 范围快照）、
 * 报名审核开关。娱乐赛不计 Rating，等级置空并禁用；比赛固化（结算）后三者均不可再改。
 */
export function useContestForm() {
  const route = useRoute()
  const userStore = useUserStore()

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
    visible: 1,
    category: 'FORMAL',
    levelCode: '',
    registrationReview: 0
  })
  // 编辑模式下"取消修改"要回到打开页面时的值，所以额外存一份 detail 快照；
// 创建模式没有快照，只能回到空白表单。
  const formSnapshot = ref(null)

  // 等级下拉选项（来自 /admin/contest-levels，仅管理员可调用）
  const levelOptions = ref([])
  const loadingLevels = ref(false)

  // 详情加载时保存的「资格规则快照」：比赛一旦固化结算，后端禁止再改分类/等级/报名审核，
  // 前端据此禁用控件，并在提交时回填快照原值，避免把未改动的 null 等级误判成"修改"。
  const ruleSnapshot = ref(null)

  const isAdmin = computed(() => userStore.role >= 3)

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

  // 已固化比赛不可再改资格规则（后端强校验）；只有编辑模式加载到详情后才可能为 true。
  const isRulesLocked = computed(() => !!ruleSnapshot.value?.finalized)

  // 等级仅对「正式赛 + 非班级比赛 + 管理员 + 未固化」开放；
  // 其他场景保留展示但禁用，避免用户误以为可以设置。
  const isLevelEditable = computed(() => {
    if (isRulesLocked.value || isClassMode.value || !isAdmin.value) return false
    return form.category === 'FORMAL'
  })

  // 等级下拉旁的说明文案：解释为什么当前不可选 / 等级含义。
  const levelHint = computed(() => {
    if (isRulesLocked.value) return '比赛已固化结算，分类/等级/报名审核不可再修改'
    if (isClassMode.value) return '班级比赛不设等级与 Rating 限制'
    if (!isAdmin.value) return '仅管理员可配置比赛等级'
    if (form.category !== 'FORMAL') return '娱乐赛不计 Rating，不设等级'
    return '等级决定参赛 Rating 范围：下限含、上限不含；不选表示不限'
  })

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

  // 换成娱乐赛立即置空等级：娱乐赛不计 Rating，保留一个等级编码没有意义。
  // 切回正式赛不自动回填，交给用户重新选择。
  watch(
    () => form.category,
    (category) => {
      if (category !== 'FORMAL') form.levelCode = ''
    }
  )

  function getDefaultForm() {
    return {
      id: null,
      title: '',
      description: '',
      startTime: null,
      endTime: null,
      mode: 'ACM',
      category: 'FORMAL',
      levelCode: '',
      registrationReview: 0
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
    ruleSnapshot.value = null
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

  function normalizeCategory(value) {
    const category = String(value || 'FORMAL').toUpperCase()
    return category === 'ENTERTAINMENT' ? 'ENTERTAINMENT' : 'FORMAL'
  }

  function fillFormFromDetail(detail) {
    const normalized = {
      id: detail?.id ?? detail?.contestId ?? null,
      title: detail?.title ?? detail?.contestName ?? '',
      description: detail?.description ?? '',
      startTime: detail?.startTime ?? detail?.beginTime ?? null,
      endTime: detail?.endTime ?? detail?.finishTime ?? null,
      mode: detail?.mode || 'ACM',
      visible: Number(detail?.visible ?? 1) === 0 ? 0 : 1,
      category: normalizeCategory(detail?.category),
      levelCode: detail?.levelCode ?? '',
      registrationReview: Number(detail?.registrationReview ?? 0) === 1 ? 1 : 0
    }

    Object.assign(form, normalized)
    formSnapshot.value = { ...normalized }
    // 固化标记不在表单里，只用于锁定资格规则控件与提交回填。
    ruleSnapshot.value = {
      finalized: !!detail?.rankFinalizedAt,
      category: normalized.category,
      levelCode: detail?.levelCode ?? null,
      registrationReview: normalized.registrationReview
    }

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

  // 比赛等级配置仅管理员可读：教师创建比赛时跳过请求，避免必然的 403 提示。
  // 失败不打断编辑器（等级只是可选项），由全局拦截器给出错误提示。
  async function loadLevelOptions() {
    if (!isAdmin.value) {
      levelOptions.value = []
      return
    }
    loadingLevels.value = true
    try {
      const res = await getContestLevelList()
      if (!isResultSuccess(res)) return
      const data = unwrapResultData(res)
      levelOptions.value = Array.isArray(data) ? data : []
    } catch (e) {
      console.error('加载比赛等级失败', e)
      levelOptions.value = []
    } finally {
      loadingLevels.value = false
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
    if (!['FORMAL', 'ENTERTAINMENT'].includes(form.category)) {
      ElMessage.warning('比赛分类参数不合法')
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
   * 资格规则三字段的提交值。
   *
   * 已固化比赛：原样回填快照（levelCode 保持 null），避免后端把"未改动"误判成改规则；
   * 未固化比赛：levelCode 传空串表示"不设等级"（后端据此清空快照），
   * 娱乐赛/班级比赛则传 null，由后端统一置空。
   */
  function buildEligibilityPayload() {
    if (isRulesLocked.value && ruleSnapshot.value) {
      return {
        category: ruleSnapshot.value.category,
        levelCode: ruleSnapshot.value.levelCode ?? null,
        registrationReview: ruleSnapshot.value.registrationReview
      }
    }

    return {
      category: form.category,
      levelCode:
        isClassMode.value || form.category === 'ENTERTAINMENT' ? null : form.levelCode || '',
      registrationReview: Number(form.registrationReview) === 1 ? 1 : 0
    }
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
        classId: classId.value ?? undefined,
        ...buildEligibilityPayload()
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
    // T10-1 资格规则相关
    levelOptions, loadingLevels, isAdmin, isRulesLocked, isLevelEditable, levelHint,
    // derived
    isCreateMode, isEditMode, contestId, classId, isClassMode, pageTitle, pageDesc,
    // actions
    resetPageState, resetForm, loadContestDetail, loadLevelOptions, validateForm, saveContest
  }
}
