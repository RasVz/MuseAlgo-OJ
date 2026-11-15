<template>
  <el-drawer
    :model-value="modelValue"
    title="用户详情"
    size="560px"
    destroy-on-close
    @update:model-value="(value) => emit('update:modelValue', value)"
  >
    <div v-if="loading && !display.id" v-loading="true" class="drawer-loading" />
    <div v-else-if="error && !display.id" class="drawer-error">
      <p>{{ error }}</p>
      <el-button :loading="loading" @click="load(userId)">重试</el-button>
    </div>
    <template v-else>
      <div class="drawer-head">
        <div class="identity-line">
          <span class="identity-name">{{ display.username || '未命名用户' }}</span>
          <el-tag :type="roleTagType(display.role)" effect="light" size="small">
            {{ roleLabel(display.role) }}
          </el-tag>
          <el-tag :type="userStatusMeta(display).type" effect="light" size="small">
            {{ userStatusMeta(display).label }}
          </el-tag>
          <span v-if="isSelf" class="self-tag">当前账号</span>
        </div>
        <div class="identity-meta">ID {{ display.id }} · 注册于 {{ formatUserTime(display.createTime) }}</div>
        <div v-if="error" class="inline-error">{{ error }}，当前展示列表中的概要信息。</div>
      </div>

      <section class="detail-section">
        <h3>账号身份</h3>
        <dl class="detail-grid">
          <div><dt>用户名</dt><dd>{{ display.username || '未填写' }}</dd></div>
          <div><dt>昵称</dt><dd>{{ display.nickname || '未填写' }}</dd></div>
          <div><dt>真实姓名</dt><dd>{{ display.realName || '未填写' }}</dd></div>
          <div><dt>邮箱</dt><dd>{{ display.email || '未填写' }}</dd></div>
          <div><dt>学校</dt><dd>{{ display.school || '未填写' }}</dd></div>
          <div><dt>专业 / 年级</dt><dd>{{ display.major || '未填写' }}</dd></div>
          <div><dt>最后登录</dt><dd>{{ formatUserTime(display.lastLoginTime) }}</dd></div>
          <div><dt>等级 / 经验</dt><dd>Lv.{{ display.level ?? '-' }} / {{ display.experience ?? 0 }}</dd></div>
        </dl>
      </section>

      <section class="detail-section">
        <h3>学习与使用</h3>
        <dl class="detail-grid">
          <div><dt>提交数</dt><dd>{{ display.submissionCount ?? 0 }}</dd></div>
          <div><dt>通过数</dt><dd>{{ display.acceptedCount ?? 0 }}</dd></div>
          <div><dt>Rating</dt><dd>{{ display.rating ?? '-' }}</dd></div>
          <div><dt>AI 用量</dt><dd>{{ Number(display.aiTokens ?? 0).toLocaleString() }} tokens</dd></div>
          <div><dt>粉丝 / 关注</dt><dd>{{ display.followerCount ?? 0 }} / {{ display.followingCount ?? 0 }}</dd></div>
          <div><dt>获赞</dt><dd>{{ display.likeCount ?? 0 }}</dd></div>
        </dl>
      </section>

      <section class="detail-section">
        <h3>封禁状态</h3>
        <div v-if="display.banActive || Number(display.isBanned) === 1" class="ban-box">
          <div class="ban-line">
            <span class="ban-label">状态</span>
            <span>{{ display.banActive ? '封禁生效中' : '封禁已过期' }}</span>
          </div>
          <div class="ban-line">
            <span class="ban-label">原因</span>
            <span>{{ display.banReason || '未填写' }}</span>
          </div>
          <div class="ban-line">
            <span class="ban-label">到期</span>
            <span>{{ display.banExpireTime ? formatUserTime(display.banExpireTime) : '永久' }}</span>
          </div>
        </div>
        <p v-else class="muted">账号当前正常，没有生效中的封禁。</p>
      </section>
    </template>

    <template #footer>
      <div class="drawer-footer">
        <el-button :disabled="!display.id || isSelf" @click="emitAction('ban')">
          {{ display.banActive ? '启用账号' : Number(display.isBanned) === 1 ? '清除封禁记录' : '禁用账号' }}
        </el-button>
        <el-button :disabled="!display.id" @click="emitAction('reset')">重置密码</el-button>
        <el-button :disabled="!display.id" @click="emitAction('audit')">审计日志</el-button>
        <el-button type="primary" :disabled="!display.id" @click="emitAction('role')">调整角色</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getAdminUserDetail } from '@/api/adminUser'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'
import { formatUserTime, roleLabel, userStatusMeta } from '@/utils/adminUserQuery'

const props = defineProps({
  modelValue: Boolean,
  userId: { type: [Number, String], default: null },
  fallbackRow: { type: Object, default: null },
  currentUserId: { type: [Number, String], default: null },
})

const emit = defineEmits(['update:modelValue', 'role', 'ban', 'reset', 'audit'])
const detail = ref(null)
const loading = ref(false)
const error = ref('')

const display = computed(() => detail.value || props.fallbackRow || {})
const isSelf = computed(() => display.value.id != null && display.value.id === props.currentUserId)

watch(
  () => [props.modelValue, props.userId],
  ([visible, id]) => {
    if (visible && id != null) {
      detail.value = null
      error.value = ''
      load(id)
    }
  },
  { immediate: true },
)

async function load(id) {
  if (id == null) return
  loading.value = true
  error.value = ''
  try {
    const res = await getAdminUserDetail(id)
    if (!isResultSuccess(res)) {
      error.value = getResultMessage(res, '用户详情加载失败')
      return
    }
    detail.value = unwrapResultData(res) || null
  } catch (cause) {
    showApiError(cause, '用户详情加载失败')
    error.value = cause?.message || '用户详情加载失败'
  } finally {
    loading.value = false
  }
}

function emitAction(action) {
  const row = display.value
  if (!row?.id) return
  if (action === 'audit') emit('audit', row)
  else emit(action, row)
}

function roleTagType(role) {
  const value = Number(role)
  if (value === 3) return 'danger'
  if (value === 2) return 'warning'
  return 'info'
}
</script>

<style scoped>
.drawer-loading {
  min-height: 220px;
}
.drawer-error {
  padding: 40px 0;
  text-align: center;
  color: var(--text-regular);
}
.drawer-head {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--border-light);
}
.identity-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.identity-name {
  font-size: 18px;
  font-weight: 650;
  color: var(--text-primary);
}
.identity-meta {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-regular);
}
.self-tag {
  color: var(--accent-primary);
  background: var(--accent-primary-light);
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 11px;
}
.inline-error {
  margin-top: 10px;
  color: var(--el-color-warning);
  font-size: 12px;
}
.detail-section {
  margin-top: 20px;
}
.detail-section h3 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
  margin: 0;
}
.detail-grid div {
  min-width: 0;
}
.detail-grid dt {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 3px;
}
.detail-grid dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ban-box {
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  background: var(--bg-app);
}
.ban-line {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: var(--text-primary);
  line-height: 1.8;
}
.ban-label {
  width: 40px;
  flex: 0 0 40px;
  color: var(--text-secondary);
}
.muted {
  color: var(--text-secondary);
  font-size: 13px;
}
.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
