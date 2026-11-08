<template>
  <div class="editor-container">
    <div class="editor-toolbar">
      <div class="toolbar-left">
        <el-select v-model="language" size="small" style="width: 120px" @change="handleLangChange">
          <el-option label="C++14 (GCC 9)" value="cpp" />
          <el-option label="Java 8" value="java" />
          <el-option label="Python 3" value="python" />
        </el-select>
        <span class="save-status">{{ saveStatusText }}</span>
      </div>

      <div class="toolbar-right">
        <el-button icon="Refresh" circle size="small" @click="resetCode" title="重置代码为初始模板" />
        <el-button icon="Setting" circle size="small" @click="settingVisible = true" title="编辑器设置" />

        <EditorSettingsDialog
          v-model="settingVisible"
          :settings="editorSettings"
          @change="onSettingsChange"
        />

        <el-button
          type="primary"
          size="small"
          :loading="submitting"
          :disabled="submitting"
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
        @change="handleCodeChange"
      />
    </div>

    <DebugConsole
      :expanded="isConsoleExpanded"
      :running="running"
      :user-input="userInput"
      :expected-output="expectedOutput"
      :run-result="runResult"
      @toggle="toggleConsole"
      @run="runCode"
      @update:user-input="userInput = $event"
      @update:expected-output="expectedOutput = $event"
    />
  </div>
</template>

<script setup>
// 代码编辑器（Monaco）：题面页的内嵌 IDE。
//
// 两条通道要分清：
// - 「运行」走 runCodeApi，只跑当前输入框里的一段数据，不产生提交记录、不计入统计；
// - 「提交」由父组件 ProblemDetailView 发起，会真正调judge并计入榜单。
//
// 代码按「题目 × 语言」维度自动存云端草稿（10 秒防抖），
// 用户切题或关页面回来能接着写；编辑器偏好则登录用户存服务端、匿名用户存 localStorage。
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { VueMonacoEditor } from '@guolao/vue-monaco-editor' 
import { getDraftCode, saveDraftCode, runCodeApi } from '@/api/problem'
import { getUserPreferences, updateUserPreferences } from '@/api/user'
import { useUserStore } from '@/stores/userStore'
import EditorSettingsDialog from './EditorSettingsDialog.vue'
import DebugConsole from './DebugConsole.vue'

const props = defineProps({
  problemId: { type: [Number, String], required: true },
  submitting: { type: Boolean, default: false }
})

const emit = defineEmits(['submit'])

// === 基础状态 ===
const language = ref('cpp')
const code = ref('')
const userInput = ref('')
const expectedOutput = ref('')
const saveStatusText = ref('') 
const running = ref(false)
const isConsoleExpanded = ref(false)

const toggleConsole = () => {
  isConsoleExpanded.value = !isConsoleExpanded.value
}

// === 设置相关状态 ===
const settingVisible = ref(false)
const editorSettings = ref({
  theme: 'vs-light',
  fontSize: 16,
  autoComplete: true,
  showLineNumbers: true
})

// === 运行结果状态 ===
const runResult = ref({
  stdout: '',
  isCorrect: false,
  statusText: '',
  statusType: ''
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

// 样例试跑的判定与正式判题不同：正式评测有逐字符比对与行尾空白规则，
// 这里只是给用户一个快速反馈，因此填了期望输出就直接字符串比较。
// 状态码取自评测机的 Judge0 语义：3=Accepted、5=TLE、6=CE，7~13 是各类运行错误。
// 合成一个 RE 是因为试跑场景下用户只关心"运行时挂了"，细分状态在这里没有价值。
const getRunStatusMeta = (status, actualOutput) => {
  const expected = expectedOutput.value.trim()

  if (expected) {
    const isCorrect = actualOutput.trim() === expected
    return {
      isCorrect,
      statusText: isCorrect ? 'AC' : 'WA',
      statusType: isCorrect ? 'ac' : 'wa'
    }
  }

  switch (Number(status)) {
    case 3:
      return { isCorrect: true, statusText: 'OK', statusType: 'ac' }
    case 5:
      return { isCorrect: false, statusText: 'TLE', statusType: 'wa' }
    case 6:
      return { isCorrect: false, statusText: 'CE', statusType: 'wa' }
    // 7~13 是各类运行时错误（越界、除零、栈溢出等）
    case 7:
    case 8:
    case 9:
    case 10:
    case 11:
    case 12:
    case 13:
      return { isCorrect: false, statusText: 'RE', statusType: 'wa' }
    default:
      return { isCorrect: false, statusText: 'ERR', statusType: 'wa' }
  }
}

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
        if (['cpp', 'java', 'python'].includes(p.language)) {
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

const loadCodeDraft = async () => {
  if (!props.problemId) return
  try {
    const res = await getDraftCode(props.problemId, language.value)
    if (res.code === 200 && res.data && res.data.code) {
      code.value = res.data.code
      saveStatusText.value = '已恢复上次的代码'
    } else {
      code.value = templates[language.value] 
    }
  } catch {
    code.value = templates[language.value]
  }
}

// 草稿按题目 × 语言分别存储，所以切语言也要重新拉一次，否则会显示另一种语言的代码。
const handleLangChange = async () => {
  await loadCodeDraft()
}

// 10 秒防抖保存草稿：逐字符保存会把接口打满（一次敲键盘就是一次请求），
// 而 10 秒的窗口既能覆盖用户的自然停顿，丢失的也只是最后几秒的输入。
let saveTimeout = null
let statusTimer = null
const handleCodeChange = () => {
  saveStatusText.value = '编辑中...'
  if (saveTimeout) clearTimeout(saveTimeout)
  
  saveTimeout = setTimeout(async () => {
    saveStatusText.value = '保存中...'
    try {
      await saveDraftCode({
        problemId: props.problemId,
        language: language.value,
        code: code.value
      })
      saveStatusText.value = '云端已保存'
      if (statusTimer) clearTimeout(statusTimer)
      statusTimer = setTimeout(() => { saveStatusText.value = '' }, 5000) 
    } catch {
      saveStatusText.value = '保存失败'
    }
  }, 10000) 
}

const resetCode = () => {
  code.value = templates[language.value]
  handleCodeChange() 
}

const fillSampleAndRun = (input, output) => {
  userInput.value = input;
  expectedOutput.value = output;
  // 填充数据时，自动展开面板并运行
  isConsoleExpanded.value = true;
  runCode(); 
}

// 「运行」是本地试跑：只把 userInput 发给评测机，不落提交记录、不影响通过率统计，
// 因此可以随便点；「提交」才是计入成绩的那一次，由父组件发起。
const runCode = async () => {
  if (!code.value.trim()) return
  
  // 只要点击运行，强制展开控制台让用户看到结果
  isConsoleExpanded.value = true;
  
  running.value = true
  runResult.value.stdout = 'Running...'
  runResult.value.isCorrect = false
  runResult.value.statusText = ''
  runResult.value.statusType = ''
  
  try {
    const res = await runCodeApi({
      code: code.value,
      language: language.value,
      input: userInput.value
    })
    
    if (res.code === 200) {
      const actual = res.data.stdout || ''
      const statusMeta = getRunStatusMeta(res.data.status, actual)
      runResult.value.stdout = actual
      runResult.value.isCorrect = statusMeta.isCorrect
      runResult.value.statusText = statusMeta.statusText
      runResult.value.statusType = statusMeta.statusType
    } else {
      runResult.value.stdout = res.message || '运行失败'
      runResult.value.statusText = 'ERR'
      runResult.value.statusType = 'wa'
    }
  } catch {
    runResult.value.stdout = 'Error: 调用评测机失败'
    runResult.value.statusText = 'ERR'
    runResult.value.statusType = 'wa'
  } finally {
    running.value = false
  }
}

// 暴露给父组件的两个能力：取提交数据（真正提交时用）与灌样例并试跑。
// 不通过 props 传递代码，是因为父组件只需要在动作发生时取一次快照。
defineExpose({ 
  getSubmissionData: () => ({ language: language.value, code: code.value }), 
  fillSampleAndRun 
})

onMounted(async () => {
  await loadSettings()
  await loadCodeDraft()
})

// 组件卸载时清理草稿保存与状态提示定时器，避免切页后幽灵请求
onUnmounted(() => {
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

.monaco-wrapper { flex: 1; border-bottom: 2px solid var(--border-light); overflow: hidden; }

.save-status { font-size: 12px; color: var(--text-secondary); margin-left: 10px; }
</style>
