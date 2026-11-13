<template>
  <div class="editor-container">
    <div class="editor-toolbar">
      <div class="toolbar-left">
        <el-select v-model="language" aria-label="编程语言" size="small" style="width: 120px" @change="handleLangChange">
          <el-option label="C++14 (GCC 9)" value="cpp" />
          <el-option label="Java 8" value="java" />
          <el-option label="Python 3" value="python" />
        </el-select>
        <span class="save-status">{{ saveStatusText }}</span>
      </div>

      <div class="toolbar-right">
        <el-button v-if="testCollapsed" size="small" @click="emit('toggle-test')">
          <el-icon class="el-icon--left"><Tickets /></el-icon> 自测
        </el-button>
        <el-button icon="Refresh" circle size="small" @click="resetCode" title="重置代码为初始模板" />
        <el-button icon="Setting" circle size="small" @click="settingVisible = true" title="编辑器设置" />

        <EditorSettingsDialog
          v-model="settingVisible"
          :settings="editorSettings"
          @change="onSettingsChange"
        />

        <el-button size="small" :disabled="submitting" @click="running ? emit('stop') : emit('run')">
          <el-icon class="el-icon--left"><component :is="running ? VideoPause : VideoPlay" /></el-icon>
          {{ running ? '停止' : '运行' }}
        </el-button>
        <el-button
          type="primary"
          size="small"
          :loading="submitting"
          :disabled="submitting || running"
          @click="handleSubmitClick"
        >
          提交代码
        </el-button>
      </div>
    </div>

    <div class="monaco-wrapper">
      <vue-monaco-editor
        v-model:value="code"
        :language="monacoLang"
        :theme="editorSettings.theme" 
        :options="editorOptions"
        @mount="handleEditorMount"
        @change="handleCodeChange"
      />
    </div>
  </div>
</template>

<script setup>
// 代码编辑器（Monaco）：题面页的内嵌 IDE。
//
// 两条通道要分清：
// - 样例试跑由父页面统一管理，可复用相同代码的结果，不产生正式提交；
// - 「提交」由父组件 ProblemDetailView 发起，会真正调judge并计入榜单。
//
// 代码默认按「题目 × 语言」维度自动存云端草稿（10 秒防抖），
// 用户切题或关页面回来能接着写；编辑器偏好则登录用户存服务端、匿名用户存 localStorage。
// 比赛/试卷场景通过 draftAdapter 改为按场次隔离的本机草稿，避免与普通练习互相污染。
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { VueMonacoEditor } from '@guolao/vue-monaco-editor' 
import { Tickets, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { getDraftCode, getLatestDraftCode, saveDraftCode } from '@/api/problem'
import { getUserPreferences, updateUserPreferences } from '@/api/user'
import { useUserStore } from '@/stores/userStore'
import EditorSettingsDialog from './EditorSettingsDialog.vue'

const props = defineProps({
  initialSnapshot: { type: Object, default: null },
  problemId: { type: [Number, String], required: true },
  submitting: { type: Boolean, default: false },
  running: { type: Boolean, default: false },
  testCollapsed: { type: Boolean, default: false },
  // { load({problemId,language}), latest({problemId}), save({problemId,language,code}) }
  draftAdapter: { type: Object, default: null },
  draftSaveDelay: { type: Number, default: 10000 }
})

const emit = defineEmits(['submit', 'run', 'stop', 'snapshot', 'toggle-test'])

// === 基础状态 ===
const pendingLanguage = props.initialSnapshot?.ready === false ? props.initialSnapshot.language : null
const initialSnapshot = props.initialSnapshot && !pendingLanguage ? { ...props.initialSnapshot } : null
const language = ref(initialSnapshot?.language || pendingLanguage || 'cpp')
const code = ref(initialSnapshot?.code || '')
const draftDirty = ref(false)
let languageChanged = false
let monacoEditor = null
const handleEditorMount = editor => { monacoEditor = editor }
const snapshotReady = ref(Boolean(initialSnapshot))
const emitSnapshot = () => { if (snapshotReady.value) emit('snapshot', { code: code.value, language: language.value, ready: true }) }
watch(code, emitSnapshot, { flush: 'sync' })
const saveStatusText = ref('')

// === 设置相关状态 ===
const settingVisible = ref(false)
const editorSettings = ref({
  theme: 'vs-light',
  fontSize: 16,
  autoComplete: true,
  showLineNumbers: true
})

const templates = {
  cpp: '#include <iostream>\nusing namespace std;\n\nint main() {\n\n    return 0;\n}',
  java: 'public class Main {\n    public static void main(String[] args) {\n\n    }\n}',
  python: ''
}

const monacoLang = computed(() => language.value === 'cpp' ? 'cpp' : language.value)

const editorOptions = computed(() => ({
  fontSize: editorSettings.value.fontSize,
  automaticLayout: true,
  minimap: { enabled: false }, 
  folding: true,               
  lineNumbers: editorSettings.value.showLineNumbers ? 'on' : 'off',
  quickSuggestions: editorSettings.value.autoComplete,
  suggestOnTriggerCharacters: editorSettings.value.autoComplete,
  readOnly: false,
  scrollBeyondLastLine: false,
}))

// === 方法区 ===

const handleSubmitClick = () => {
  if (props.submitting) return
  emit('submit')
}

let loadedPrefs = null

const isLoggedIn = () => !!useUserStore().getAccessToken()

// 偏好要"服务端优先、localStorage 兜底"：登录用户换设备时也应保留字号与主题，
// 但服务端不可用（离线、接口失败）不能让编辑器退回默认体验。
const loadSettings = async () => {
  // 1) 登录用户优先读取服务端偏好（默认语言/字号/主题/自动补全/行号）
  if (isLoggedIn()) {
    try {
      const res = await getUserPreferences()
      if (res.code === 200 && res.data) {
        loadedPrefs = res.data
        const p = res.data
        editorSettings.value.fontSize = Number(p.fontSize) || 16
        if (['vs-light', 'vs-dark'].includes(p.editorTheme)) {
          editorSettings.value.theme = p.editorTheme
        }
        editorSettings.value.autoComplete = p.autoComplete !== false
        editorSettings.value.showLineNumbers = p.showLineNumbers !== false
        if (!initialSnapshot && !pendingLanguage && !languageChanged && !draftDirty.value && ['cpp', 'java', 'python'].includes(p.language)) {
          language.value = p.language
        }
        return
      }
    } catch {
      // 读取失败则回退本地设置
    }
  }

  // 2) 匿名或读取失败时回退本地设置
  const saved = localStorage.getItem('musealgo_editor_settings')
  if (saved) {
    try {
      editorSettings.value = { ...editorSettings.value, ...JSON.parse(saved) }
    } catch {
      // 忽略损坏的本地设置
    }
  }
}

// 本地先写、服务端后写：即使后端失败，用户这次的编辑器外观也已立即生效。
const saveSettings = async () => {
  localStorage.setItem('musealgo_editor_settings', JSON.stringify(editorSettings.value))
  if (!isLoggedIn()) return
  try {
    const prefs = { ...(loadedPrefs || {}), ...editorSettings.value }
    prefs.fontSize = editorSettings.value.fontSize
    prefs.editorTheme = editorSettings.value.theme
    prefs.autoComplete = editorSettings.value.autoComplete
    prefs.showLineNumbers = editorSettings.value.showLineNumbers
    const res = await updateUserPreferences(prefs)
    if (res.code === 200 && res.data) {
      loadedPrefs = res.data
    }
  } catch {
    // 服务端保存失败时本地设置仍生效
  }
}

const onSettingsChange = (patch) => {
  editorSettings.value = { ...editorSettings.value, ...patch }
  saveSettings()
}

let draftGeneration = 0
const draftLoading = ref(!initialSnapshot)

// 草稿读写有两套来源：普通练习走服务端，比赛/试卷走场景壳注入的本地适配器。
// 适配器只提供 load/latest/save 三个动作，编辑器不关心草稿存在哪里。
const readDraft = async language => {
  if (props.draftAdapter?.load) {
    return (await props.draftAdapter.load({ problemId: props.problemId, language })) || null
  }
  const res = await getDraftCode(props.problemId, language)
  return res.data || null
}
const readLatestDraft = async () => {
  if (props.draftAdapter?.latest) {
    return (await props.draftAdapter.latest({ problemId: props.problemId })) || null
  }
  const res = await getLatestDraftCode(props.problemId)
  return res.data || null
}
const persistDraft = async () => {
  if (props.draftAdapter?.save) {
    await props.draftAdapter.save({
      problemId: props.problemId,
      language: language.value,
      code: code.value
    })
    return '本机已保存'
  }
  await saveDraftCode({
    problemId: props.problemId,
    language: language.value,
    code: code.value
  })
  return '云端已保存'
}

const loadCodeDraft = async () => {
  if (!props.problemId) return
  const generation = ++draftGeneration
  const selectedLanguage = language.value
  const originalCode = code.value
  snapshotReady.value = false
  draftLoading.value = true
  try {
    const data = await readDraft(selectedLanguage)
    if (generation !== draftGeneration || selectedLanguage !== language.value || code.value !== originalCode || draftDirty.value) return
    code.value = data?.code ?? templates[selectedLanguage]
    saveStatusText.value = data?.code ? (props.draftAdapter ? '已恢复本机草稿' : '已恢复上次的代码') : ''
  } catch {
    if (generation === draftGeneration && selectedLanguage === language.value && code.value === originalCode && !draftDirty.value) code.value = templates[selectedLanguage]
  } finally {
    if (generation === draftGeneration) { draftLoading.value = false; snapshotReady.value = true; emitSnapshot() }
  }
}

// 草稿按题目 × 语言分别存储，所以切语言也要重新拉一次，否则会显示另一种语言的代码。
const handleLangChange = async () => {
  languageChanged = true
  emit('snapshot', { language: language.value, code: '', ready: false })
  draftDirty.value = false
  await loadCodeDraft()
}

// 防抖保存草稿：逐字符保存会把接口打满（一次敲键盘就是一次请求）。
// 普通练习沿用 10 秒窗口；比赛/试卷可以传更短的 draftSaveDelay，尽量少丢代码。
let saveTimeout = null
let statusTimer = null
const handleCodeChange = () => {
  draftDirty.value = true
  snapshotReady.value = true
  emitSnapshot()
  saveStatusText.value = '编辑中...'
  if (saveTimeout) clearTimeout(saveTimeout)

  const delay = Math.max(500, Number(props.draftSaveDelay) || 10000)
  saveTimeout = setTimeout(async () => {
    saveStatusText.value = '保存中...'
    try {
      const savedText = await persistDraft()
      saveStatusText.value = savedText
      if (statusTimer) clearTimeout(statusTimer)
      statusTimer = setTimeout(() => { saveStatusText.value = '' }, 5000)
    } catch {
      saveStatusText.value = '保存失败'
    }
  }, delay)
}

const resetCode = () => {
  code.value = templates[language.value]
  handleCodeChange() 
}

// 父组件在提交或试跑时读取当前模型快照。
// 当前模型供动作发生时读取；有效草稿同步给父组件，用于离开 IDE 后继续提问。
defineExpose({ 
  getSubmissionData: () => {
    if (draftLoading.value && !draftDirty.value) throw new Error('代码草稿正在加载，请稍后再试')
    return { language: language.value, code: monacoEditor?.getValue() ?? code.value }
  }
})

onMounted(async () => {
  await loadSettings()
  if (languageChanged) return
  if (initialSnapshot || draftDirty.value) draftLoading.value = false
  if (pendingLanguage && !draftDirty.value) { await loadCodeDraft(); return }
  if (!initialSnapshot && !draftDirty.value) {
    const originalLanguage = language.value
    const initialGeneration = draftGeneration
    draftLoading.value = true
    try {
      const data = await readLatestDraft()
      if (initialGeneration !== draftGeneration || draftDirty.value || language.value !== originalLanguage) return
      if (data) {
        language.value = data.language || 'cpp'
        code.value = data.code || ''
      } else code.value = templates[language.value]
    } catch {
      if (initialGeneration === draftGeneration && !draftDirty.value) code.value = templates[language.value]
    } finally {
      if (initialGeneration === draftGeneration) { draftLoading.value = false; snapshotReady.value = true; emitSnapshot() }
    }
  }
})

// 组件卸载时清理草稿保存与状态提示定时器，避免切页后幽灵请求
onUnmounted(() => {
  draftGeneration++
  if (saveTimeout) clearTimeout(saveTimeout)
  if (statusTimer) clearTimeout(statusTimer)
})
</script>

<style scoped>
.editor-container { height: 100%; display: flex; flex-direction: column; background: var(--bg-surface); }

.editor-toolbar {
  height: 40px; padding: 0 15px; display: flex; justify-content: space-between;
  align-items: center; background: var(--bg-input); border-bottom: 1px solid var(--border-light);
}

.monaco-wrapper { min-height: 180px; flex: 1; overflow: hidden; }

.save-status { font-size: 12px; color: var(--text-secondary); margin-left: 10px; }
</style>
