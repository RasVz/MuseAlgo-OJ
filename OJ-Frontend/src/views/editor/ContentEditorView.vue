<template>
  <div class="content-editor-page">
    <header class="editor-topbar">
      <div class="brand">
        <span class="brand-title">写作台</span>
        <span v-if="isEdit" class="brand-badge edit">编辑中</span>
      </div>

      <div class="channel-switch">
        <button
          v-for="item in channelList"
          :key="item.key"
          type="button"
          :class="{ active: channel === item.key }"
          :disabled="isEdit && channel !== item.key"
          @click="selectChannel(item.key)"
        >{{ item.label }}</button>
      </div>

      <input v-model="form.title" class="title-input" placeholder="输入标题…" maxlength="128" />

      <div class="top-actions">
        <span class="stats">{{ stats }}</span>
        <el-button size="small" @click="copyMarkdown">复制 Markdown</el-button>
        <el-button type="primary" size="small" :loading="saving" :disabled="loading" @click="handleSave">
          {{ isEdit ? '更新' : '发布' }}
        </el-button>
      </div>
    </header>

    <main v-loading="loading" class="editor-body">
      <section class="editor-main">
        <div class="channel-hint">
          <span>{{ channelConfig.hint }}</span>
          <span v-if="!isEdit" class="draft-status">{{ draftStatus }}</span>
        </div>
        <BlockEditor ref="blockEditor" v-model="form.content" :placeholder="channelConfig.placeholder" />
      </section>

      <EditorMetaPanel :form="form" :channel="channel" :tag-groups="tagGroups" @update-field="applyField" />
    </main>
  </div>
</template>

<script setup>
// 通用写作台（方案 B 块编辑器）：独立标签页，不继承站内布局。
// 正文以 Markdown 存储；保存前做往返校验，无法无损序列化时阻止提交。
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import BlockEditor from './components/BlockEditor.vue'
import EditorMetaPanel from './components/EditorMetaPanel.vue'
import { CHANNELS, buildPayload, channelByKey, resolveChannel } from './channels'
import { createPost, getPostDetail, getPostTagOptions, updatePost } from '@/api/post'
import { UnsupportedMarkdownError, checkRoundTrip, markdownToDoc } from '@/utils/markdown-blocks'
import { showApiError } from '@/utils/errorFeedback'

const DRAFT_KEY = 'musealgo:editor:draft:new'
const DRAFT_DEBOUNCE = 800

const route = useRoute()
const router = useRouter()

const channelList = Object.values(CHANNELS)
const channel = ref('article')
const loading = ref(false)
const saving = ref(false)
const draftStatus = ref('草稿会自动保存到本机')
const tagGroups = ref([])
const allowedTags = ref(new Set())
const blockEditor = ref(null)
const initialSnapshot = ref('')

const form = reactive({
  id: null,
  title: '',
  type: 2,
  category: 'algorithm',
  tags: [],
  summary: '',
  content: '',
  problemId: null,
  privacy: 0,
  relatedProblemIds: []
})

const isEdit = computed(() => Boolean(form.id))
const channelConfig = computed(() => channelByKey(channel.value))

const parseTags = (value) => {
  if (Array.isArray(value)) return value.filter(Boolean)
  const text = String(value || '').trim()
  if (!text) return []
  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) return parsed.filter(Boolean)
  } catch {
    /* 历史数据是逗号分隔 */
  }
  return text.replace(/\[|\]|"/g, '').split(',').map((tag) => tag.trim()).filter(Boolean)
}

const stats = computed(() => {
  const text = form.content.replace(/```[\s\S]*?```/g, ' ').replace(/[#>*`_[\]()-]/g, ' ')
  const cjk = (text.match(/[\u4e00-\u9fa5]/g) || []).length
  const words = (text.replace(/[\u4e00-\u9fa5]/g, ' ').match(/[A-Za-z0-9]+/g) || []).length
  return `${cjk + words} 字`
})

const snapshot = () => JSON.stringify({
  id: form.id,
  title: form.title,
  type: form.type,
  category: form.category,
  tags: form.tags,
  summary: form.summary,
  content: form.content,
  problemId: form.problemId,
  privacy: form.privacy,
  relatedProblemIds: form.relatedProblemIds,
  channel: channel.value
})

const isDirty = computed(() => snapshot() !== initialSnapshot.value)

// 元信息面板通过事件回传，页面统一写入表单，避免子组件直接改 props。
const applyField = (field, value) => {
  form[field] = value
}

const applyChannel = (key) => {
  const config = channelByKey(key)
  form.type = config.type
  if (config.key === 'solution') {
    form.category = 'solution'
    const fromQuery = Number(route.query.problemId)
    if (Number.isSafeInteger(fromQuery) && fromQuery > 0) form.problemId = fromQuery
  } else {
    form.problemId = null
    if (!config.categories.some((item) => item.value === form.category)) {
      form.category = config.defaultCategory
    }
  }
}

const selectChannel = (key) => {
  if (isEdit.value || channel.value === key) return
  channel.value = key
  applyChannel(key)
}

const copyMarkdown = async () => {
  const markdown = blockEditor.value?.getMarkdown() ?? ''
  if (!markdown.trim()) {
    ElMessage.info('还没有内容')
    return
  }
  try {
    await navigator.clipboard.writeText(markdown)
    ElMessage.success('Markdown 已复制')
  } catch {
    ElMessage.error('复制失败，请检查浏览器权限')
  }
}

const loadTagOptions = async () => {
  try {
    const res = await getPostTagOptions()
    if (res.code !== 200) return
    tagGroups.value = Object.entries(res.data || {}).map(([label, options]) => ({ label, options }))
    allowedTags.value = new Set(tagGroups.value.flatMap((group) => group.options))
  } catch {
    /* 标签加载失败不阻塞写作，保存时后端仍会校验 */
  }
}

// 旧编辑器承接班级内容与历史特殊结构：这里给出明确出口，不半途改坏数据。
const openLegacyEditor = (id, reason) => {
  ElMessageBox.alert(reason, '该内容暂不支持在新编辑器编辑', {
    confirmButtonText: '用旧编辑器打开',
    cancelButtonText: '返回我的内容',
    showCancelButton: true,
    type: 'warning'
  }).then(() => window.location.replace(`/post/editor/${id}`)).catch(() => router.replace('/my/post'))
}

const loadPost = async (id) => {
  loading.value = true
  try {
    const res = await getPostDetail(id)
    if (res.code !== 200 || !res.data) {
      ElMessage.error(res.message || '加载文章失败')
      return
    }
    const data = res.data
    const resolved = resolveChannel(data.type, data.category)
    if (resolved === 'class') {
      openLegacyEditor(id, '班级内容带有 classId，更新接口不接受跨频道修改。')
      return
    }
    try {
      markdownToDoc(data.content || '')
    } catch (error) {
      if (error instanceof UnsupportedMarkdownError) {
        openLegacyEditor(id, `正文包含暂不支持的结构：${error.unsupported.join('、')}`)
        return
      }
      throw error
    }

    channel.value = resolved
    form.id = data.id
    form.title = data.title || ''
    form.type = data.type ?? channelByKey(resolved).type
    form.category = data.category || channelByKey(resolved).defaultCategory
    form.summary = data.summary || ''
    form.content = data.content || ''
    form.privacy = Number(data.privacy ?? 0)
    form.problemId = data.problemId || null
    form.tags = parseTags(data.tags).filter((tag) => !allowedTags.value.size || allowedTags.value.has(tag))
    form.relatedProblemIds = (data.relatedProblems || [])
      .map((problem) => Number(problem.id))
      .filter(Number.isSafeInteger)
  } catch (error) {
    showApiError(error, '加载文章失败')
  } finally {
    loading.value = false
  }
}

const restoreDraft = async () => {
  const raw = localStorage.getItem(DRAFT_KEY)
  if (!raw) return
  try {
    const draft = JSON.parse(raw)
    const saved = draft?.form
    if (!saved?.content && !saved?.title) return
    await ElMessageBox.confirm('检测到未发布的本地草稿，是否继续编辑？', '恢复草稿', {
      confirmButtonText: '继续',
      cancelButtonText: '放弃草稿',
      type: 'info'
    })
    Object.assign(form, { ...form, ...saved, id: null })
    if (CHANNELS[draft.channel]) {
      channel.value = draft.channel
      applyChannel(draft.channel)
    }
    ElMessage.success('已恢复本地草稿')
  } catch (error) {
    if (error instanceof SyntaxError) localStorage.removeItem(DRAFT_KEY)
  }
}

const saveDraft = () => {
  if (isEdit.value || !isDirty.value) return
  localStorage.setItem(DRAFT_KEY, JSON.stringify({
    form: { ...form, tags: [...form.tags], relatedProblemIds: [...form.relatedProblemIds] },
    channel: channel.value,
    savedAt: Date.now()
  }))
  draftStatus.value = '已自动保存到本机'
}

const handleSave = async () => {
  const { payload, error } = buildPayload({ channel: channel.value, form })
  if (error) {
    ElMessage.warning(error)
    return
  }

  const doc = blockEditor.value?.getDoc()
  const issue = doc ? checkRoundTrip(doc) : { reason: '编辑器尚未就绪' }
  if (import.meta.env.DEV) console.log('[save] guard', issue)
  if (issue) {
    ElMessageBox.alert(`无法保存：${issue.reason}`, '内容结构不受支持', {
      confirmButtonText: '返回修改',
      type: 'warning'
    })
    return
  }

  saving.value = true
  try {
    const res = isEdit.value ? await updatePost(payload) : await createPost(payload)
    if (res.code !== 200) {
      ElMessage.error(res.message || '保存失败')
      return
    }
    localStorage.removeItem(DRAFT_KEY)
    initialSnapshot.value = snapshot()
    draftStatus.value = '草稿会自动保存到本机'
    ElMessage.success(isEdit.value ? '已更新' : '发布成功')
    if (!isEdit.value) {
      setTimeout(() => {
        if (window.opener) window.close()
        else router.push('/my/post')
      }, 800)
    }
  } catch (err) {
    showApiError(err, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

const handleBeforeUnload = (event) => {
  if (!isDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(async () => {
  if (!isDirty.value || saving.value) return true
  try {
    await ElMessageBox.confirm('内容尚未保存，确定离开？', '离开确认', {
      confirmButtonText: '离开',
      cancelButtonText: '继续编辑',
      type: 'warning'
    })
    return true
  } catch {
    return false
  }
})

let draftTimer
watch(() => snapshot(), () => {
  if (isEdit.value) return
  draftStatus.value = '正在保存草稿…'
  clearTimeout(draftTimer)
  draftTimer = setTimeout(saveDraft, DRAFT_DEBOUNCE)
})

onMounted(async () => {
  await loadTagOptions()

  const id = Number(route.query.id)
  if (id) {
    await loadPost(id)
  } else {
    const requested = String(route.query.channel || '')
    if (CHANNELS[requested]) channel.value = requested
    else if (route.query.type === '2') channel.value = 'solution'
    applyChannel(channel.value)
    await restoreDraft()
  }

  initialSnapshot.value = snapshot()
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onBeforeUnmount(() => {
  clearTimeout(draftTimer)
  saveDraft()
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<style scoped>
.content-editor-page {
  min-height: 100vh;
  background: var(--bg-app);
  display: flex;
  flex-direction: column;
}
.editor-topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 14px;
  height: 60px;
  padding: 0 20px;
  background: var(--bg-header, var(--bg-surface));
  border-bottom: 1px solid var(--border-light);
  box-shadow: var(--shadow-sm);
}
.brand { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.brand-title { font-size: 15px; font-weight: 600; color: var(--text-primary); }
.brand-badge {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--accent-primary-light);
  color: var(--accent-primary);
  font-size: 11px;
}
.brand-badge.edit { background: var(--accent-warning-light, #fdf6ec); color: var(--accent-warning, #b88230); }
.channel-switch {
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 8px;
  background: var(--bg-app);
  flex-shrink: 0;
}
.channel-switch button {
  padding: 4px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
}
.channel-switch button.active {
  background: var(--bg-surface);
  color: var(--accent-primary);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}
.channel-switch button:disabled { cursor: default; opacity: 0.5; }
.channel-switch button.active:disabled { opacity: 1; }
.title-input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  color: var(--text-primary);
  font-size: 18px;
  font-weight: 600;
  outline: none;
}
.title-input::placeholder { color: var(--text-placeholder); font-weight: 400; }
.top-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.stats { color: var(--text-secondary); font-size: 12px; white-space: nowrap; }

.editor-body {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  width: 100%;
  max-width: 1240px;
  margin: 0 auto;
  padding: 20px 20px 60px;
  align-items: start;
}
.editor-main {
  min-width: 0;
  padding: 16px 24px 8px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  box-shadow: var(--shadow-sm);
}
.channel-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 4px;
  border-radius: 6px;
  background: var(--accent-primary-light);
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.draft-status { color: var(--text-placeholder); white-space: nowrap; }
@media (max-width: 1080px) {
  .editor-body { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 720px) {
  .editor-topbar { height: auto; padding: 10px 12px; flex-wrap: wrap; gap: 8px; }
  .title-input { order: 3; width: 100%; flex-basis: 100%; }
  .editor-body { padding: 12px; }
  .editor-main { padding: 12px; }
  .draft-status { display: none; }
}
</style>
