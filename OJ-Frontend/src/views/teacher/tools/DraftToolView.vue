<template>
  <div class="draft-page">
    <header class="draft-head">
      <div class="head-main">
        <div class="head-title-row">
          <h1 class="head-title">题面生成</h1>
          <el-tag v-if="activeDraft" size="small" effect="plain" :type="activeStatus.type">
            {{ activeStatus.label }}
          </el-tag>
          <span v-if="activeDraft && !activeStatus.terminal" class="head-elapsed">
            {{ activeStatus.phaseLabel }} · 已等待 {{ formatElapsed(activeDraft.elapsedMs) }}
          </span>
        </div>
        <p class="head-desc">
          填写命题简报，AI 生成完整题面；结果会保存到历史，可逐字段修改、定向重写，确认后再落库为私有题目。
        </p>
      </div>
      <div class="head-actions">
        <el-button v-if="activeDraft?.cancellable" size="small" @click="cancelActive">取消</el-button>
        <el-button text :aria-expanded="collapsed ? 'false' : 'true'" aria-controls="draft-brief" @click="toggleBrief">
          {{ collapsed ? '展开简报' : '收起简报' }}
        </el-button>
        <el-button :icon="Clock" @click="openHistory">历史</el-button>
        <el-button type="primary" plain :disabled="busy" @click="startNew">新建</el-button>
      </div>
    </header>

    <div class="draft-body" :class="{ 'is-collapsed': collapsed }">
      <section id="draft-brief" class="draft-col draft-col-brief">
        <DraftBriefPanel
          v-if="!collapsed"
          :brief="brief"
          :variants="variants"
          :disabled="busy"
          :busy="busy"
          :collapsed="false"
          :prompt-preview="promptPreview"
          :prompt-loading="promptLoading"
          @update:brief="onBriefChange"
          @update:variants="variants = $event"
          @generate="generate"
          @preview-prompt="previewPrompt"
          @quick-start="applyQuickStart"
        />
        <button v-else type="button" class="brief-rail" @click="toggleBrief">
          <el-icon><Document /></el-icon>
          <span>命题简报</span>
        </button>
      </section>

      <section class="draft-col draft-col-canvas">
        <div v-if="!activeDraft" class="empty-state">
          <el-icon class="empty-icon"><MagicStick /></el-icon>
          <h2>还没有生成结果</h2>
          <p>在左侧选好知识点与难度，点「生成题面」。一次可以生成 1~3 个方案做对比。</p>
          <p class="empty-hint">生成在服务端执行，关掉页面也不会丢；回到本页会自动恢复最近一次结果。</p>
        </div>
        <DraftCanvas
          v-else
          :draft="detail || activeDraft"
          :variants="items"
          :active-id="activeId"
          :editing-field="editingField"
          :similar="similar"
          @update:active-id="switchVariant"
          @toggle-edit="editingField = $event"
          @update:content="onContentChange"
          @refresh-similar="loadSimilar"
          @retry="retryActive"
          @delete="removeActive"
        />
      </section>

      <section class="draft-col draft-col-review">
        <DraftReviewPanel
          :draft="detail || activeDraft"
          :saving="saving"
          :can-save="Boolean(detail) && activeDraft?.status === 'READY'"
          @save="saveActive"
          @rewrite="rewriteActive"
          @locate-issue="locateIssue"
        />
      </section>
    </div>

    <DraftHistoryDrawer
      v-model="historyVisible"
      :items="historyItems"
      :loading="historyLoading"
      :total="historyTotal"
      :page="historyPage"
      :page-size="historySize"
      @open="openFromHistory"
      @remove="removeFromHistory"
      @reuse-brief="reuseBrief"
      @page-change="loadHistory"
      @refresh="loadHistory(historyPage)"
    />

    <p class="sr-only" role="status" aria-live="polite">{{ liveMessage }}</p>
  </div>
</template>

<script setup>
// 题面生成工作台：左简报、中画布、右审阅。
//
// 关键设计：模型调用在服务端异步执行，页面只负责受理与轮询。
// 因此关掉页面不会丢结果，重新进入时按「最近一条未完成或 24 小时内的结果」自动恢复。
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, Document, MagicStick } from '@element-plus/icons-vue'
import { showApiError } from '@/utils/errorFeedback'
import { isResultSuccess, unwrapResultData, getResultMessage } from '@/utils/response'
import {
  cancelDraft,
  createDraft,
  deleteDraft,
  getDraft,
  listDrafts,
  previewDraftPrompt,
  retryDraft,
  rewriteDraft,
  saveDraft,
  similarDrafts,
  updateDraftContent
} from '@/api/teacherAi'
import DraftBriefPanel from './draft/DraftBriefPanel.vue'
import DraftCanvas from './draft/DraftCanvas.vue'
import DraftReviewPanel from './draft/DraftReviewPanel.vue'
import DraftHistoryDrawer from './draft/DraftHistoryDrawer.vue'
import {
  DEFAULT_BRIEF,
  formatElapsed,
  isTerminal,
  normalizeBrief,
  statusMeta,
  validateBrief
} from './draft/draftModel'

const route = useRoute()
const router = useRouter()

const POLL_MS = 1500
const RESTORE_WINDOW_MS = 24 * 60 * 60 * 1000

const brief = ref(normalizeBrief({ ...DEFAULT_BRIEF }))
const variants = ref(1)
const collapsed = ref(false)
const busy = ref(false)
const saving = ref(false)
const promptPreview = ref(null)
const promptLoading = ref(false)

const items = ref([])
const activeId = ref(null)
const detail = ref(null)
const content = ref(null)
const dirty = ref(false)
const editingField = ref(null)
const similar = ref([])

const historyVisible = ref(false)
const historyItems = ref([])
const historyLoading = ref(false)
const historyTotal = ref(0)
const historyPage = ref(1)
const historySize = 10

const liveMessage = ref('')
let pollTimer = null
let pollFailures = 0

const activeDraft = computed(() => items.value.find(item => item.id === activeId.value) || items.value[0] || null)
const activeStatus = computed(() => statusMeta(activeDraft.value?.status, activeDraft.value?.phase))
const groupId = computed(() => items.value[0]?.groupId || null)

const announce = (text) => {
  liveMessage.value = text
}

const toggleBrief = () => {
  collapsed.value = !collapsed.value
}

const onBriefChange = (value) => {
  brief.value = value
}

const applyQuickStart = (preset) => {
  brief.value = normalizeBrief({ ...DEFAULT_BRIEF, ...preset })
  announce('已套用快速开始模板')
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const startPolling = () => {
  stopPolling()
  pollFailures = 0
  pollTimer = setInterval(refreshGroup, POLL_MS)
}

/**
 * 轮询整组而不是单条：3 个方案只需要一个轮询器，且能同时更新方案切换条上的问题数。
 */
const refreshGroup = async () => {
  const group = groupId.value
  if (!group) {
    stopPolling()
    return
  }
  try {
    const res = await listDrafts({ groupId: group })
    if (!isResultSuccess(res)) {
      throw new Error(getResultMessage(res, '查询生成进度失败'))
    }
    pollFailures = 0
    const page = unwrapResultData(res) || {}
    const next = Array.isArray(page.items) ? page.items : []
    if (!next.length) return
    const previous = items.value.find(item => item.id === activeId.value)
    items.value = next
    const current = next.find(item => item.id === activeId.value) || next[0]
    if (current && current.id !== activeId.value) {
      activeId.value = current.id
    }
    // 激活方案刚变成可审阅，或状态推进了，才重新拉详情，避免每 1.5s 拉一次全文
    const becameReady = Boolean(previous) && previous.status !== 'READY' && current?.status === 'READY'
    const statusChanged = Boolean(previous) && current && previous.status !== current.status
    if (current && current.status === 'READY' && (becameReady || statusChanged)) {
      await loadDetail(current.id)
    }
    if (becameReady) {
      announce('生成完成，可以开始审阅')
    }
    if (next.every(item => isTerminal(item.status))) {
      stopPolling()
    }
  } catch {
    pollFailures += 1
    if (pollFailures >= 5) {
      stopPolling()
      announce('查询生成进度连续失败，请稍后手动重试')
    }
  }
}

const loadDetail = async (id) => {
  const res = await getDraft(id)
  if (!isResultSuccess(res)) {
    throw new Error(getResultMessage(res, '加载草稿失败'))
  }
  const data = unwrapResultData(res)
  if (!data) {
    throw new Error('草稿内容为空')
  }
  detail.value = data
  content.value = data.content ? JSON.parse(JSON.stringify(data.content)) : null
  dirty.value = false
  editingField.value = null
  if (data.brief) {
    brief.value = normalizeBrief(data.brief)
  }
  announce(`已加载草稿：${data.draft?.title || '未命名'}`)
  if (data.draft?.status === 'READY') {
    loadSimilar()
  }
  return data
}

const loadSimilar = async () => {
  const id = activeDraft.value?.id
  if (!id) {
    similar.value = []
    return
  }
  try {
    const res = await similarDrafts(id)
    similar.value = isResultSuccess(res) ? unwrapResultData(res) || [] : []
  } catch {
    similar.value = []
  }
}

const setActiveGroup = async (list, preferredId) => {
  items.value = Array.isArray(list) ? list : []
  const target = items.value.find(item => item.id === preferredId) || items.value[0]
  activeId.value = target ? target.id : null
  detail.value = null
  content.value = null
  dirty.value = false
  similar.value = []
  if (!target) return
  if (target.status === 'READY') {
    await loadDetail(target.id)
  }
  if (!isTerminal(target.status)) {
    startPolling()
  }
  router.replace({ query: { ...route.query, draftId: String(target.id) } })
}

const generate = async () => {
  const errors = validateBrief(brief.value)
  if (errors.length) {
    ElMessage.warning(errors[0])
    return
  }
  busy.value = true
  try {
    const res = await createDraft({ brief: normalizeBrief(brief.value), variants: variants.value })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '受理生成失败'))
      return
    }
    const created = unwrapResultData(res) || []
    await setActiveGroup(created, created[0]?.id)
    collapsed.value = created.length > 0
    announce('已受理生成，正在服务端执行')
  } catch (error) {
    showApiError(error, '受理生成失败，请稍后重试')
  } finally {
    busy.value = false
  }
}

const switchVariant = async (id) => {
  if (!id || id === activeId.value) return
  activeId.value = id
  detail.value = null
  content.value = null
  similar.value = []
  dirty.value = false
  router.replace({ query: { ...route.query, draftId: String(id) } })
  const target = items.value.find(item => item.id === id)
  if (target?.status === 'READY') {
    try {
      await loadDetail(id)
    } catch (error) {
      showApiError(error, '加载方案失败')
    }
  }
}

const onContentChange = (next) => {
  // 详情没加载成功时不能接收编辑：否则会把只有一两个字段的对象当整份题面提交
  if (!content.value) return
  content.value = next
  dirty.value = true
  // 与服务端一致：内容一改，上一次落库的题目就不再对应这份草稿。
  // 同步清掉本地 problemId，否则审阅栏会把「保存」按钮锁在「已保存」状态，改完反而存不了。
  if (detail.value?.draft?.problemId) {
    detail.value = {
      ...detail.value,
      draft: { ...detail.value.draft, problemId: null, savedFields: [] }
    }
  }
}

const ensureContentSaved = async () => {
  if (!detail.value || !content.value || !dirty.value) return
  const res = await updateDraftContent(detail.value.draft.id, content.value)
  if (!isResultSuccess(res)) {
    throw new Error(getResultMessage(res, '保存修改失败'))
  }
  const data = unwrapResultData(res)
  if (data) {
    detail.value = data
    content.value = data.content ? JSON.parse(JSON.stringify(data.content)) : null
    dirty.value = false
    items.value = items.value.map(item => (item.id === data.draft.id ? { ...item, ...data.draft } : item))
  }
}

const saveActive = async (payload) => {
  const id = activeDraft.value?.id
  if (!id) return
  saving.value = true
  try {
    // 先落人工修改再建题：否则题库里拿到的是模型原始稿，教师改的字全部丢失
    await ensureContentSaved()
    const res = await saveDraft(id, payload)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '保存失败'))
      return
    }
    const result = unwrapResultData(res) || {}
    if (result.created === false) {
      ElMessage.info(`这份草稿此前已保存为题目 #${result.problemId}`)
    } else {
      ElMessage.success(`已保存为私有题目 #${result.problemId}`)
    }
    if (detail.value) {
      detail.value = {
        ...detail.value,
        draft: { ...detail.value.draft, problemId: result.problemId, savedFields: result.appliedFields || [] }
      }
    }
    items.value = items.value.map(item =>
      item.id === id ? { ...item, problemId: result.problemId, savedFields: result.appliedFields || [] } : item
    )
  } catch (error) {
    showApiError(error, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

const rewriteActive = async ({ targetField, instruction }) => {
  const id = activeDraft.value?.id
  if (!id) return
  busy.value = true
  try {
    await ensureContentSaved()
    const res = await rewriteDraft(id, { targetField, instruction })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '发起重写失败'))
      return
    }
    const data = unwrapResultData(res)
    if (data?.draft) {
      ElMessage.success('已受理定向重写，结果会作为新方案出现')
      await openDraft(data.draft.id)
    }
  } catch (error) {
    showApiError(error, '发起重写失败')
  } finally {
    busy.value = false
  }
}

const retryActive = async () => {
  const id = activeDraft.value?.id
  if (!id) return
  try {
    const res = await retryDraft(id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '重试失败'))
      return
    }
    const updated = unwrapResultData(res)
    items.value = items.value.map(item => (item.id === id ? { ...item, ...updated } : item))
    startPolling()
    announce('已重新排队')
  } catch (error) {
    showApiError(error, '重试失败')
  }
}

const removeActive = async () => {
  const id = activeDraft.value?.id
  if (!id) return
  try {
    await ElMessageBox.confirm(
      '删除只丢弃这次生成的结果；如果模型调用已经发出，仍会照常计费。确定删除？',
      '删除本次生成',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留' }
    )
  } catch {
    return
  }
  try {
    const res = await deleteDraft(id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '删除失败'))
      return
    }
    const remaining = items.value.filter(item => item.id !== id)
    stopPolling()
    if (remaining.length) {
      await setActiveGroup(remaining, remaining[0].id)
    } else {
      clearResult()
    }
    ElMessage.success('已删除')
  } catch (error) {
    showApiError(error, '删除失败')
  }
}

const cancelActive = async () => {
  const id = activeDraft.value?.id
  if (!id) return
  try {
    const res = await cancelDraft(id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '取消失败'))
      return
    }
    const updated = unwrapResultData(res)
    items.value = items.value.map(item => (item.id === id ? { ...item, ...updated } : item))
    announce('已取消')
  } catch (error) {
    showApiError(error, '取消失败')
  }
}

const locateIssue = (field) => {
  editingField.value = field
  const target = document.getElementById(`draft-field-${field}`)
  if (target) {
    target.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }
}

const previewPrompt = async () => {
  const errors = validateBrief(brief.value)
  if (errors.length) {
    ElMessage.warning(errors[0])
    return
  }
  promptLoading.value = true
  try {
    const res = await previewDraftPrompt({ brief: normalizeBrief(brief.value) })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '获取提示词失败'))
      return
    }
    promptPreview.value = unwrapResultData(res) || null
  } catch (error) {
    showApiError(error, '获取提示词失败')
  } finally {
    promptLoading.value = false
  }
}

const clearResult = () => {
  stopPolling()
  items.value = []
  activeId.value = null
  detail.value = null
  content.value = null
  dirty.value = false
  editingField.value = null
  similar.value = []
  const query = { ...route.query }
  delete query.draftId
  router.replace({ query })
}

const startNew = () => {
  if (dirty.value && !window.confirm('当前修改尚未保存，确定新建？')) return
  clearResult()
  collapsed.value = false
}

const openDraft = async (id) => {
  try {
    const res = await getDraft(id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '加载草稿失败'))
      return
    }
    const vo = unwrapResultData(res)?.draft
    if (!vo) return
    const groupRes = await listDrafts({ groupId: vo.groupId })
    const list = isResultSuccess(groupRes) ? unwrapResultData(groupRes)?.items || [] : [vo]
    await setActiveGroup(list, vo.id)
    collapsed.value = list.length > 0
  } catch (error) {
    showApiError(error, '加载草稿失败')
  }
}

const openHistory = () => {
  historyVisible.value = true
  loadHistory(1)
}

const loadHistory = async (page = 1) => {
  historyLoading.value = true
  historyPage.value = page
  try {
    const res = await listDrafts({ page, size: historySize })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '加载历史失败'))
      return
    }
    const data = unwrapResultData(res) || {}
    historyItems.value = data.items || []
    historyTotal.value = data.total || 0
  } catch (error) {
    showApiError(error, '加载历史失败')
  } finally {
    historyLoading.value = false
  }
}

const openFromHistory = async (id) => {
  historyVisible.value = false
  await openDraft(id)
}

const removeFromHistory = async (id) => {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除这条生成记录？', '删除记录', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    const res = await deleteDraft(id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '删除失败'))
      return
    }
    if (id === activeDraft.value?.id) {
      clearResult()
    }
    await loadHistory(historyPage.value)
  } catch (error) {
    showApiError(error, '删除失败')
  }
}

const reuseBrief = async (item) => {
  try {
    const res = await getDraft(item.id)
    if (isResultSuccess(res)) {
      const data = unwrapResultData(res)
      if (data?.brief) {
        brief.value = normalizeBrief(data.brief)
        historyVisible.value = false
        collapsed.value = false
        ElMessage.success('已把上次的命题简报填回左侧')
      }
    }
  } catch (error) {
    showApiError(error, '读取简报失败')
  }
}

/**
 * 自动恢复：优先 URL 里的 draftId；否则取最近一条，只在「未完成或 24 小时内」时打开，
 * 避免一进页面就弹出上个月的旧题面。
 */
const restore = async () => {
  const draftId = Number(route.query.draftId)
  if (draftId) {
    await openDraft(draftId)
    return
  }
  try {
    const res = await listDrafts({ page: 1, size: 1 })
    if (!isResultSuccess(res)) return
    const latest = unwrapResultData(res)?.items?.[0]
    if (!latest) return
    const recent = latest.createTime && Date.now() - new Date(latest.createTime).getTime() < RESTORE_WINDOW_MS
    if (!isTerminal(latest.status) || recent) {
      await openDraft(latest.id)
      collapsed.value = true
    }
  } catch {
    // 恢复失败不打扰用户：停在空态即可，点「生成题面」仍可用
  }
}

watch(
  () => route.query.draftId,
  (value) => {
    const id = Number(value)
    if (id && id !== activeId.value) {
      openDraft(id)
    }
  }
)

onMounted(restore)

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.draft-page {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--bg-app);
}
.draft-head {
  flex: 0 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-surface);
}
.head-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.head-title {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  color: var(--text-primary);
}
.head-elapsed {
  font-size: 12px;
  color: var(--text-secondary);
}
.head-desc {
  margin: 6px 0 0;
  max-width: 820px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-regular);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.draft-body {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 16px;
  padding: 16px 20px;
  overflow: hidden;
  grid-template-columns: clamp(300px, 24vw, 360px) minmax(0, 1fr) clamp(280px, 21vw, 320px);
}
.draft-body.is-collapsed {
  grid-template-columns: 44px minmax(0, 1fr) clamp(280px, 21vw, 320px);
}
.draft-col {
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
}
.brief-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: 40px;
  height: 100%;
  padding: 12px 0;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  color: var(--text-regular);
  font-size: 12px;
  cursor: pointer;
}
.brief-rail span {
  writing-mode: vertical-rl;
  letter-spacing: 2px;
}
.brief-rail:hover {
  border-color: var(--border-hover);
  color: var(--accent-primary);
}
.empty-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 24px;
  text-align: center;
  border: 1px dashed var(--border-base);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
  color: var(--text-secondary);
}
.empty-icon {
  font-size: 34px;
  color: var(--accent-primary);
}
.empty-state h2 {
  margin: 4px 0 0;
  font-size: 17px;
  color: var(--text-primary);
}
.empty-state p {
  margin: 0;
  max-width: 460px;
  font-size: 13px;
  line-height: 1.7;
}
.empty-hint {
  color: var(--text-secondary);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}
@media (max-width: 1200px) {
  .draft-page {
    overflow: auto;
  }
  .draft-body,
  .draft-body.is-collapsed {
    grid-template-columns: 1fr;
    overflow: visible;
  }
  .draft-col {
    overflow: visible;
  }
  .brief-rail {
    width: 100%;
    height: 44px;
    flex-direction: row;
    justify-content: center;
  }
  .brief-rail span {
    writing-mode: horizontal-tb;
  }
}
</style>
