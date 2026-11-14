<template>
  <div class="exam-workspace">
    <header class="workspace-topbar">
      <div class="topbar-left">
        <el-button text class="back-button" @click="$emit('back')">
          <el-icon><Back /></el-icon>
          <span>返回</span>
        </el-button>
        <span class="paper-title" :title="title">{{ title }}</span>
        <el-tag v-if="statusText" size="small" effect="plain">{{ statusText }}</el-tag>
      </div>

      <div class="topbar-right">
        <span class="timer" :class="{ warning: remainingSeconds <= 300 }" aria-live="polite">
          {{ formattedRemaining }}
        </span>
        <el-button v-if="navItems.length" size="small" @click="navigatorVisible = true">
          <el-icon class="el-icon--left"><Tickets /></el-icon>
          答题卡
        </el-button>
        <el-button
          v-if="showSaveObjective"
          size="small"
          :loading="savingObjective"
          :disabled="finishing"
          @click="$emit('save-objective')"
        >
          提交客观题
        </el-button>
        <el-button
          v-if="showFinish"
          type="primary"
          size="small"
          :loading="finishing"
          :disabled="!canSubmit"
          @click="$emit('finish')"
        >
          交卷
        </el-button>
      </div>
    </header>

    <div class="workspace-body">
      <aside v-if="navItems.length" class="question-rail" aria-label="题号导航">
        <div class="rail-title">答题卡</div>
        <QuestionNavigator :items="navItems" :active-key="activeKey" @select="selectQuestion" />
      </aside>

      <main class="question-main">
        <slot />
      </main>
    </div>

    <el-drawer v-if="navItems.length" v-model="navigatorVisible" title="答题卡" size="320px" direction="rtl">
      <QuestionNavigator :items="navItems" :active-key="activeKey" @select="selectQuestion" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Back, Tickets } from '@element-plus/icons-vue'
import QuestionNavigator from './QuestionNavigator.vue'

const props = defineProps({
  title: { type: String, default: '成套试卷' },
  statusText: { type: String, default: '' },
  remainingSeconds: { type: Number, default: 0 },
  navItems: { type: Array, default: () => [] },
  activeKey: { type: String, default: '' },
  canSubmit: { type: Boolean, default: false },
  finishing: { type: Boolean, default: false },
  savingObjective: { type: Boolean, default: false },
  showSaveObjective: { type: Boolean, default: false },
  showFinish: { type: Boolean, default: true }
})

const emit = defineEmits(['select', 'back', 'finish', 'save-objective'])

const navigatorVisible = ref(false)

const formattedRemaining = computed(() => {
  const seconds = Math.max(0, Number(props.remainingSeconds) || 0)
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  const pad = value => String(value).padStart(2, '0')
  return hours > 0 ? `${pad(hours)}:${pad(minutes)}:${pad(rest)}` : `${pad(minutes)}:${pad(rest)}`
})

const selectQuestion = key => {
  navigatorVisible.value = false
  emit('select', key)
}
</script>

<style scoped>
.exam-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--bg-app);
  overflow: hidden;
}

.workspace-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 52px;
  padding: 8px 18px;
  border-bottom: 1px solid var(--border-base);
  background: var(--bg-surface);
  flex-shrink: 0;
}
.topbar-left,
.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.back-button { padding-left: 0; }
.paper-title {
  max-width: 38vw;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 700;
  color: var(--text-primary);
}
.timer {
  min-width: 82px;
  text-align: right;
  font-family: monospace;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.timer.warning { color: var(--accent-danger, #f56c6c); }

.workspace-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

.question-rail {
  width: 190px;
  flex-shrink: 0;
  padding: 14px 12px;
  border-right: 1px solid var(--border-base);
  background: var(--bg-surface);
  overflow-y: auto;
}
.rail-title {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
}

.question-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg-app);
}

@media (max-width: 1180px) {
  .question-rail { display: none; }
  .paper-title { max-width: 30vw; }
}
</style>
