<template>
  <div class="settings-tab">
    <h2 class="settings-tab-title">偏好设置</h2>
    <p class="settings-tab-note">编辑器、隐私与通知偏好保存在账号中；界面主题只保存在本设备。</p>

    <SettingsSection title="界面" description="选择站点外观，切换后立即生效。">
      <el-form :label-position="labelPosition" :label-width="labelWidth" class="settings-form">
        <SettingsRow title="界面主题" description="浅色 / 深色 / 跟随系统，仅保存在本设备">
          <template #default="{ describedBy }">
            <el-radio-group
              v-model="uiTheme"
              :aria-describedby="describedBy"
              @change="onUiThemeChange"
            >
              <el-radio-button value="light">浅色</el-radio-button>
              <el-radio-button value="dark">深色</el-radio-button>
              <el-radio-button value="system">跟随系统</el-radio-button>
            </el-radio-group>
          </template>
        </SettingsRow>
      </el-form>
    </SettingsSection>

    <SettingsSection
      title="编辑器"
      description="新建题目代码时的默认值。"
      :dirty="groupDirty('editor')"
    >
      <template #actions>
        <el-button v-if="groupDirty('editor')" text @click="resetGroup('editor')">取消本组修改</el-button>
      </template>
      <el-form :label-position="labelPosition" :label-width="labelWidth" class="settings-form">
        <SettingsRow title="默认语言" description="打开代码编辑器时预选的语言">
          <template #default="{ describedBy }">
            <el-select v-model="localPref.language" :aria-describedby="describedBy" class="settings-select">
              <el-option label="C++" value="cpp" />
              <el-option label="Java" value="java" />
              <el-option label="Python 3" value="python" />
            </el-select>
          </template>
        </SettingsRow>

        <SettingsRow title="代码字号" description="12–24px">
          <template #default="{ describedBy }">
            <el-slider
              v-model="localPref.fontSize"
              :min="12"
              :max="24"
              show-input
              :aria-describedby="describedBy"
              class="settings-slider"
            />
          </template>
        </SettingsRow>

        <SettingsRow title="编辑器主题" description="仅影响代码编辑器，不改变站点外观">
          <template #default="{ describedBy }">
            <el-select v-model="localPref.editorTheme" :aria-describedby="describedBy" class="settings-select">
              <el-option label="明亮 (Light)" value="vs-light" />
              <el-option label="暗黑 (Dark)" value="vs-dark" />
            </el-select>
          </template>
        </SettingsRow>

        <SettingsRow title="自动补全" description="输入时显示代码补全建议">
          <template #default="{ describedBy }">
            <el-switch v-model="localPref.autoComplete" :aria-describedby="describedBy" />
          </template>
        </SettingsRow>

        <SettingsRow title="显示行号" description="在编辑器左侧显示行号">
          <template #default="{ describedBy }">
            <el-switch v-model="localPref.showLineNumbers" :aria-describedby="describedBy" />
          </template>
        </SettingsRow>
      </el-form>
    </SettingsSection>

    <SettingsSection
      title="隐私"
      description="控制他人能看到的内容。"
      :dirty="groupDirty('privacy')"
    >
      <template #actions>
        <el-button v-if="groupDirty('privacy')" text @click="resetGroup('privacy')">取消本组修改</el-button>
      </template>
      <el-form :label-position="labelPosition" :label-width="labelWidth" class="settings-form">
        <SettingsRow title="分享提交记录" description="关闭后，你的提交记录不会出现在「所有人 / 关注的人」列表中">
          <template #default="{ describedBy }">
            <el-switch v-model="localPref.shareSubmissions" :aria-describedby="describedBy" />
          </template>
        </SettingsRow>
        <SettingsRow title="公开主页" description="关闭后，未登录用户无法查看你的个人主页">
          <template #default="{ describedBy }">
            <el-switch v-model="localPref.profilePublic" :aria-describedby="describedBy" />
          </template>
        </SettingsRow>
      </el-form>
    </SettingsSection>

    <SettingsSection
      title="通知"
      description="选择希望收到的站内通知类型。"
      :dirty="groupDirty('notification')"
    >
      <template #actions>
        <el-button v-if="groupDirty('notification')" text @click="resetGroup('notification')">
          取消本组修改
        </el-button>
      </template>
      <el-form :label-position="labelPosition" :label-width="labelWidth" class="settings-form">
        <SettingsRow title="互动通知" description="点赞、评论、回复、关注等">
          <template #default="{ describedBy }">
            <el-switch v-model="localPref.notifications.interactions" :aria-describedby="describedBy" />
          </template>
        </SettingsRow>
        <SettingsRow title="作业提醒" description="作业发布与截止时间提醒">
          <template #default="{ describedBy }">
            <el-switch v-model="localPref.notifications.homework" :aria-describedby="describedBy" />
          </template>
        </SettingsRow>
        <SettingsRow title="比赛提醒" description="比赛开始与结束提醒">
          <template #default="{ describedBy }">
            <el-switch v-model="localPref.notifications.contest" :aria-describedby="describedBy" />
          </template>
        </SettingsRow>
        <SettingsRow title="学习报告通知" description="每周学习报告生成后通知我">
          <template #default="{ describedBy }">
            <el-switch v-model="localPref.notifications.reports" :aria-describedby="describedBy" />
          </template>
        </SettingsRow>
      </el-form>
    </SettingsSection>

    <div class="preference-actions">
      <el-button type="primary" :loading="prefLoading" @click="handleSave">保存偏好</el-button>
      <el-button :disabled="!anyDirty" @click="resetAll">取消更改</el-button>
      <el-button plain @click="$emit('reset')">恢复默认</el-button>
      <span v-if="anyDirty" class="unsaved-hint" role="status">
        有未保存的修改，将随「保存偏好」一起提交
      </span>
    </div>
  </div>
</template>

<script setup>
/**
 * 偏好设置 Tab（展示组件）。
 * pref 为 useUserSettings 持有的 reactive 对象（含嵌套 notifications）；
 * 本地深拷贝编辑（不直接改 props），保存时 save 事件携带完整载荷由父组件合并落库。
 * 后端 /user/preferences 以默认值为基线做整包覆盖，因此保存必须提交完整的 pref，
 * 各分区只提供本地「取消本组修改」，不伪装成分区独立落库。
 */
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { deepClone } from '@/utils/deepClone'
import SettingsRow from './SettingsRow.vue'
import SettingsSection from './SettingsSection.vue'

const props = defineProps({
  pref: { type: Object, required: true },
  prefLoading: { type: Boolean, default: false }
})

const emit = defineEmits(['save', 'reset'])

const narrow = inject('settingsNarrow', ref(false))
const labelPosition = computed(() => (narrow.value ? 'top' : 'left'))
const labelWidth = computed(() => (narrow.value ? 'auto' : '220px'))

// 必须用 deepClone：props.pref 是 reactive 代理，structuredClone 会抛 DataCloneError。
const localPref = ref(deepClone(props.pref))
const baseline = ref(deepClone(props.pref))
// 父组件回填（fillPref）/恢复默认（resetPref）时同步草稿与基线
watch(() => props.pref, (v) => {
  localPref.value = deepClone(v)
  baseline.value = deepClone(v)
}, { deep: true })

const groupKeys = {
  editor: ['language', 'fontSize', 'editorTheme', 'autoComplete', 'showLineNumbers'],
  privacy: ['shareSubmissions', 'profilePublic'],
  notification: ['notifications']
}

function keyChanged(key) {
  return JSON.stringify(localPref.value[key]) !== JSON.stringify(baseline.value[key])
}

function groupDirty(group) {
  return (groupKeys[group] || []).some(keyChanged)
}

const anyDirty = computed(() => ['editor', 'privacy', 'notification'].some(groupDirty))

function resetGroup(group) {
  const next = deepClone(localPref.value)
  const keys = groupKeys[group] || []
  keys.forEach((key) => {
    next[key] = deepClone(baseline.value[key])
  })
  localPref.value = next
}

function resetAll() {
  localPref.value = deepClone(baseline.value)
}

function handleSave() {
  emit('save', deepClone(localPref.value))
  // 提交即视为新基线；失败时由共享反馈层提示，用户可再次保存。
  baseline.value = deepClone(localPref.value)
}

// 界面主题只存 localStorage 不入服务端偏好：
// 深浅色是设备属性（同一账号在手机和电脑上往往偏好相反），跟用户身份无关。
const THEME_KEY = 'oj-dark-mode'
const uiTheme = ref('system')
const media = window.matchMedia('(prefers-color-scheme: dark)')

// 只有「跟随系统」模式下才需要监听系统主题变化；
// 手动指定时系统切了也不该改用户的显式选择。
const syncFromSystem = () => {
  const m = localStorage.getItem(THEME_KEY)
  if (m === 'system' || m === null) {
    document.documentElement.classList.toggle('dark', media.matches)
  }
}

const onUiThemeChange = (v) => {
  localStorage.setItem(THEME_KEY, v)
  document.documentElement.classList.toggle('dark', v === 'dark' || (v === 'system' && media.matches))
  if (v === 'system') {
    media.addEventListener('change', syncFromSystem)
  } else {
    media.removeEventListener('change', syncFromSystem)
  }
}

// 兼容早期把主题存成 '1'/'0' 的旧数据
onMounted(() => {
  const m = localStorage.getItem(THEME_KEY)
  uiTheme.value = m === 'dark' || m === '1' ? 'dark' : m === 'light' || m === '0' ? 'light' : 'system'
  if (uiTheme.value === 'system') {
    media.addEventListener('change', syncFromSystem)
  }
})

// 卸载时解绑系统主题监听：matchMedia 的监听不会随组件销毁自动移除。
onUnmounted(() => media.removeEventListener('change', syncFromSystem))
</script>

<style scoped>
.settings-tab-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
}
.settings-tab-note {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-regular);
}
.settings-select {
  width: min(100%, 220px);
}
.settings-slider {
  width: min(100%, 320px);
}
.preference-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 20px;
  border-top: 1px solid var(--border-light);
}
.unsaved-hint {
  font-size: 12px;
  color: var(--text-regular);
}
</style>
