<template>
  <div class="config-page">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">运行时配置</span>
        <span class="panel-hint">DB 覆盖 · 保存后秒级生效 · 逐项提交</span>
      </div>
      <el-alert v-if="serviceManaged" type="info" show-icon :closable="false" class="block"
        title="当前由后台服务提供 AI"
        description="模型、输出上限与 JSON 模式来自当前服务，本页只读；请到「AI 服务」编辑并测试。" />
      <el-alert v-if="configError" :title="configError" type="error" :closable="false" class="block" />

      <div class="config-grid">
        <div class="group">
          <div class="group-head">
            <span class="group-title">配额策略</span>
            <div>
              <el-button v-if="groupDirty(quotaKeys)" size="small" @click="resetGroup(quotaKeys)">撤销</el-button>
              <el-button type="primary" size="small" :loading="savingGroup === 'quota'" :disabled="!groupDirty(quotaKeys)" @click="saveGroup(quotaKeys, 'quota')">保存本组</el-button>
            </div>
          </div>
          <el-form label-width="170px">
            <el-form-item label="配额开关">
              <el-switch v-model="editable['ai.quota.enabled']" active-value="true" inactive-value="false" />
            </el-form-item>
            <el-form-item label="每日配额 token">
              <el-input-number v-model="editable['ai.quota.daily-tokens']" :min="0" :step="1000" />
            </el-form-item>
            <el-form-item label="教师/管理员豁免">
              <el-switch v-model="editable['ai.quota.exempt-teacher-admin']" active-value="true" inactive-value="false" />
            </el-form-item>
          </el-form>
        </div>

        <div class="group">
          <div class="group-head">
            <span class="group-title">服务参数</span>
            <el-button v-if="!serviceManaged" type="primary" size="small"
              :loading="savingGroup === 'service'" :disabled="!groupDirty(serviceKeys)"
              @click="saveGroup(serviceKeys, 'service')">保存本组</el-button>
            <el-tag v-else size="small" type="info">只读</el-tag>
          </div>
          <el-form label-width="170px">
            <el-form-item label="模型">
              <el-input v-model="editable['ai.model']" :disabled="serviceManaged" />
            </el-form-item>
            <el-form-item label="max_tokens">
              <el-input-number v-model="editable['ai.max-tokens']" :min="256" :step="1024" :disabled="serviceManaged" />
            </el-form-item>
            <el-form-item label="JSON 模式">
              <el-switch v-model="editable['ai.json-mode']" active-value="true" inactive-value="false" :disabled="serviceManaged" />
            </el-form-item>
          </el-form>
        </div>

        <div class="group">
          <div class="group-head"><span class="group-title">教学使用范围</span></div>
          <p class="group-text">比赛场景禁用，普通练习开放。该策略由后端固定，不在本页修改。</p>
          <p class="group-text">运行模式：<b>{{ runtimeModeLabel }}</b> · 配额策略：{{ config.dailyTokens ?? '—' }} token / 人·日</p>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">提示词模板版本</span>
        <el-select v-model="promptKey" style="width: 200px" @change="loadPrompts">
          <el-option label="开放答疑" value="tutor-open" />
          <el-option label="开放复盘" value="review-open" />
        </el-select>
      </div>
      <div class="prompt-create">
        <el-input v-model="promptNewContent" type="textarea" :rows="3" placeholder="新建版本内容（创建后自动激活）" />
        <el-button type="primary" :loading="creatingPrompt" @click="createPrompt">新建版本并激活</el-button>
      </div>
      <el-table v-loading="loadingPrompts" :data="prompts" size="small" max-height="280" empty-text="暂无模板版本">
        <el-table-column prop="promptKey" label="key" width="130" />
        <el-table-column prop="version" label="版本" width="80" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isActive === 1 ? 'success' : 'info'" size="small">{{ row.isActive === 1 ? '激活' : '历史' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="150" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="selectedPrompt = row">查看</el-button>
            <el-button v-if="row.isActive !== 1" link type="primary" @click="activatePrompt(row)">激活</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="selectedPrompt" class="prompt-preview">
        <div class="preview-head">
          <span>版本 {{ selectedPrompt.version }} · {{ selectedPrompt.isActive === 1 ? '当前激活' : '历史版本' }}</span>
          <el-button link type="primary" @click="selectedPrompt = null">收起</el-button>
        </div>
        <pre class="preview-body">{{ selectedPrompt.content }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup>
// 配置与模板面板：配置按后端白名单键分组保存，提示词版本从弹窗改为常驻区块。
// 逐 key 串行提交，任一项失败即中断并让父组件回读真实配置，避免界面与服务端不一致。
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  updateAiConfig, getPromptTemplates, createPromptTemplate, activatePromptTemplate
} from '@/api/adminAi'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const props = defineProps({ config: { type: Object, default: () => ({}) } })
const emit = defineEmits(['saved'])

// 必须与后端 AiAdminController.CONFIG_KEYS 的点分隔键名完全一致。
const quotaKeys = ['ai.quota.enabled', 'ai.quota.daily-tokens', 'ai.quota.exempt-teacher-admin']
const serviceKeys = ['ai.model', 'ai.max-tokens', 'ai.json-mode']

const editable = reactive({
  'ai.quota.enabled': 'true',
  'ai.quota.daily-tokens': 30000,
  'ai.quota.exempt-teacher-admin': 'true',
  'ai.model': '',
  'ai.max-tokens': 8192,
  'ai.json-mode': 'true'
})
const baseline = ref({})
const savingGroup = ref('')
const configError = ref('')

const serviceManaged = computed(() => props.config.runtimeMode === 'MANAGED')
const runtimeModeLabel = computed(() => ({
  LEGACY: '旧环境', MANAGED: '后台服务', OFF: '已停用', UNKNOWN: '未知'
}[props.config.runtimeMode] || '未知'))

const syncFromConfig = () => {
  const c = props.config || {}
  editable['ai.quota.enabled'] = String(c.quotaEnabled ?? true)
  editable['ai.quota.daily-tokens'] = c.dailyTokens ?? 30000
  editable['ai.quota.exempt-teacher-admin'] = String(c.exemptTeacherAdmin ?? true)
  editable['ai.model'] = c.model || ''
  editable['ai.max-tokens'] = c.maxTokens ?? 8192
  editable['ai.json-mode'] = String(c.jsonMode ?? true)
  baseline.value = { ...editable }
}
watch(() => props.config, syncFromConfig, { immediate: true, deep: true })

const groupDirty = (keys) => keys.some((key) => String(editable[key]) !== String(baseline.value[key]))
const resetGroup = (keys) => keys.forEach((key) => { editable[key] = baseline.value[key] })

const saveGroup = async (keys, name) => {
  savingGroup.value = name
  configError.value = ''
  try {
    for (const key of keys) {
      const res = await updateAiConfig(key, String(editable[key]))
      if (!isResultSuccess(res)) throw new Error(getResultMessage(res, `保存 ${key} 失败`))
    }
    ElMessage.success('配置已保存，秒级生效')
    emit('saved')
  } catch (error) {
    configError.value = '配置未完整保存，部分选项可能已生效；已重新加载实际配置，请核对后重试。'
    showApiError(error, '配置保存失败')
    emit('saved')
  } finally {
    savingGroup.value = ''
  }
}

const promptKey = ref('tutor-open')
const promptNewContent = ref('')
const prompts = ref([])
const loadingPrompts = ref(false)
const creatingPrompt = ref(false)
const selectedPrompt = ref(null)

const loadPrompts = async () => {
  loadingPrompts.value = true
  try {
    const res = await getPromptTemplates(promptKey.value || undefined)
    prompts.value = unwrapResultData(res) || []
  } catch (error) {
    showApiError(error, '提示词版本加载失败')
  } finally {
    loadingPrompts.value = false
  }
}

const createPrompt = async () => {
  if (!promptNewContent.value.trim()) {
    ElMessage.warning('请输入模板内容')
    return
  }
  creatingPrompt.value = true
  try {
    const res = await createPromptTemplate(promptKey.value || 'tutor-open', promptNewContent.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '创建失败'))
      return
    }
    ElMessage.success('新版本已创建并激活')
    promptNewContent.value = ''
    selectedPrompt.value = null
    loadPrompts()
  } catch (error) {
    showApiError(error, '创建失败')
  } finally {
    creatingPrompt.value = false
  }
}

const activatePrompt = async (row) => {
  try {
    const res = await activatePromptTemplate(row.id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '激活失败'))
      return
    }
    ElMessage.success('已激活')
    selectedPrompt.value = null
    loadPrompts()
  } catch (error) {
    showApiError(error, '激活失败')
  }
}

onMounted(loadPrompts)
</script>

<style scoped>
.config-page { display: flex; flex-direction: column; gap: 12px; }
.panel { min-width: 0; padding: 12px 14px; background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-md); }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; }
.panel-title { color: var(--text-primary); font-size: 14px; font-weight: 600; }
.panel-hint { color: var(--text-secondary); font-size: 12px; }
.block { margin-bottom: 12px; }
.config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.config-grid > .group:last-child { grid-column: 1 / -1; }
.group { padding: 12px; background: var(--bg-input); border-radius: var(--radius-sm); }
.group-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.group-title { color: var(--text-primary); font-size: 13px; font-weight: 600; }
.group-text { margin: 6px 0; color: var(--text-regular); font-size: 12px; line-height: 1.6; }
.prompt-create { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; margin-bottom: 12px; }
.prompt-preview { margin-top: 12px; padding: 10px 12px; background: var(--bg-input); border-radius: var(--radius-sm); }
.preview-head { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--text-regular); }
.preview-body { max-height: 220px; margin: 8px 0 0; overflow: auto; color: var(--text-primary); white-space: pre-wrap; word-break: break-word; font-family: var(--font-mono); font-size: 12px; line-height: 1.6; }
@media (max-width: 1000px) { .config-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
