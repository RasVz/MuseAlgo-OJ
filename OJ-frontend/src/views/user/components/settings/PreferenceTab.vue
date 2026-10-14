<template>
  <div class="tab-content">
    <h3 class="tab-title">偏好设置</h3>

    <div class="pref-group">
      <div class="pref-group-title">界面</div>
      <el-form label-position="left" label-width="110px" class="setting-form">
        <el-form-item label="界面主题">
          <el-radio-group v-model="uiTheme" @change="onUiThemeChange">
            <el-radio-button value="light">浅色</el-radio-button>
            <el-radio-button value="dark">深色</el-radio-button>
            <el-radio-button value="system">跟随系统</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
    </div>

    <div class="pref-group">
      <div class="pref-group-title">编辑器</div>
      <el-form label-position="left" label-width="110px" class="setting-form">
        <el-form-item label="默认语言">
          <el-select v-model="localPref.language" style="width: 180px">
            <el-option label="C++" value="cpp" />
            <el-option label="Java" value="java" />
            <el-option label="Python 3" value="python" />
          </el-select>
        </el-form-item>

        <el-form-item label="代码字号">
          <el-slider v-model="localPref.fontSize" :min="12" :max="24" show-input style="width: 260px" />
        </el-form-item>

        <el-form-item label="编辑器主题">
          <el-select v-model="localPref.editorTheme" style="width: 180px">
            <el-option label="明亮 (Light)" value="vs-light" />
            <el-option label="暗黑 (Dark)" value="vs-dark" />
          </el-select>
        </el-form-item>

        <el-form-item label="自动补全">
          <el-switch v-model="localPref.autoComplete" />
        </el-form-item>

        <el-form-item label="显示行号">
          <el-switch v-model="localPref.showLineNumbers" />
        </el-form-item>
      </el-form>
    </div>

    <div class="pref-group">
      <div class="pref-group-title">隐私</div>
      <el-form label-position="left" label-width="110px" class="setting-form">
        <el-form-item label="分享提交记录">
          <el-switch v-model="localPref.shareSubmissions" />
          <span class="pref-tip">关闭后，你的提交记录不会出现在"所有人 / 关注的人"列表中</span>
        </el-form-item>
        <el-form-item label="公开主页">
          <el-switch v-model="localPref.profilePublic" />
          <span class="pref-tip">关闭后，未登录用户无法查看你的个人主页</span>
        </el-form-item>
      </el-form>
    </div>

    <div class="pref-group">
      <div class="pref-group-title">通知</div>
      <el-form label-position="left" label-width="110px" class="setting-form">
        <el-form-item label="互动通知">
          <el-switch v-model="localPref.notifications.interactions" />
          <span class="pref-tip">点赞、评论、回复、关注等</span>
        </el-form-item>
        <el-form-item label="作业提醒">
          <el-switch v-model="localPref.notifications.homework" />
        </el-form-item>
        <el-form-item label="比赛提醒">
          <el-switch v-model="localPref.notifications.contest" />
        </el-form-item>
      </el-form>
    </div>

    <div class="pref-actions">
      <el-button type="primary" :loading="prefLoading" @click="handleSave">保存偏好</el-button>
      <el-button plain @click="$emit('reset')">恢复默认</el-button>
    </div>
  </div>
</template>

<script setup>
/**
 * 偏好设置 Tab（展示组件）。
 * pref 为 useUserSettings 持有的 reactive 对象（含嵌套 notifications），直接双向绑定。
 * uiTheme 为界面主题（浅色/深色/跟随系统），仅存 localStorage，不入服务端偏好。
 */
import { ref, watch, onMounted, onUnmounted } from 'vue'

/**
 * 偏好设置 Tab（展示组件）。
 * pref 为 useUserSettings 持有的 reactive 对象（含嵌套 notifications）；
 * 本地深拷贝编辑（不直接改 props），保存时 save 事件携带最新载荷由父组件合并落库。
 * uiTheme 为界面主题（浅色/深色/跟随系统），仅存 localStorage，不入服务端偏好。
 */
const props = defineProps({
  pref: { type: Object, required: true },
  prefLoading: { type: Boolean, default: false }
})

const emit = defineEmits(['save', 'reset'])

// 本地深拷贝：通知是嵌套对象（notifications.interactions 等），
// 直接 v-model 绑 props.pref 会连带改掉父组件的状态，"取消"按钮就没得撤回了。
const localPref = ref(structuredClone(props.pref))
// 父组件重置（resetPref）/回填（fillPref）时同步到本地
watch(() => props.pref, (v) => {
  localPref.value = structuredClone(v)
}, { deep: true })

function handleSave() {
  emit('save', structuredClone(localPref.value))
}

// 界面主题只存 localStorage 不入服务端偏好：
// 深浅色是设备属性（同一账号在手机和电脑上往往偏好相反），跟用户身份无关。
const THEME_KEY = 'oj-dark-mode'
const uiTheme = ref('system')
const media = window.matchMedia('(prefers-color-scheme: dark)')

// 只有"跟随系统"模式下才需要监听系统主题变化；
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

// 卸载时解绑系统主题监听：matchMedia 的监听不会随组件销毁自动移除，
// 反复进出设置页会叠加多个监听。
onUnmounted(() => media.removeEventListener('change', syncFromSystem))
</script>

<style scoped>
.tab-content {
  padding: 0 40px 20px;
}
.tab-title {
  margin-top: 0;
  margin-bottom: 25px;
  font-size: 20px;
  color: var(--text-primary);
}
.setting-form {
  max-width: 600px;
}
.pref-tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
.pref-group {
  border: 1px solid var(--border-light);
  border-radius: 12px;
  padding: 16px 20px 4px;
  margin-bottom: 18px;
  background: var(--bg-surface-hover);
}
.pref-group-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 12px;
}
.pref-actions {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}
</style>
