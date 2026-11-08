<template>
  <div class="settlement-replay admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">结算链式重放</div>
        <div class="page-desc">从该比赛起，重算其后所有已固化比赛的榜单与 Rating（仅管理员，高风险）</div>
      </div>
      <div>
        <el-button :loading="loading" @click="loadPreview">刷新预览</el-button>
        <el-button @click="router.back()">返回</el-button>
      </div>
    </div>

    <el-alert
      title="执行会重写链内 contest_rank 与 user_rating，并重算 user.rating；单事务失败会整体回滚。执行前请确认已有数据库备份。"
      type="error"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <el-card shadow="never" v-loading="loading" class="mb-16">
      <template v-if="preview">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="起点比赛">{{ preview.fromContestTitle }}（#{{ preview.fromContestId }}）</el-descriptions-item>
          <el-descriptions-item label="链场次">{{ preview.contests?.length || 0 }}</el-descriptions-item>
          <el-descriptions-item label="受影响用户">{{ preview.affectedUsers }}</el-descriptions-item>
          <el-descriptions-item label="旧成绩行数">{{ preview.totalOldRows }}</el-descriptions-item>
          <el-descriptions-item label="是否可执行">
            <el-tag :type="preview.canExecute ? 'success' : 'danger'">{{ preview.canExecute ? '是' : '否' }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="preview.warnings?.length"
          title="前置校验未通过"
          type="error"
          :closable="false"
          show-icon
          class="mt-16"
        >
          <ul class="warn-list">
            <li v-for="(warning, index) in preview.warnings" :key="index">{{ warning }}</li>
          </ul>
        </el-alert>

        <el-divider content-position="left">链范围（按结束时间/ID 升序）</el-divider>
        <el-table :data="preview.contests || []" size="small" style="width: 100%">
          <el-table-column prop="id" label="比赛 ID" width="100" />
          <el-table-column prop="title" label="比赛" min-width="260" />
          <el-table-column label="结束时间" width="180">
            <template #default="{ row }">{{ formatTime(row.endTime) }}</template>
          </el-table-column>
          <el-table-column prop="settlementNo" label="当前结算轮次" width="130" align="center" />
        </el-table>
      </template>
      <el-empty v-else-if="!loading" description="未获取到预览数据" />
    </el-card>

    <el-card v-if="preview" shadow="never">
      <el-form label-width="130px" style="max-width: 720px">
        <el-form-item label="重放原因" required>
          <el-input v-model.trim="reason" type="textarea" :rows="3" maxlength="500" show-word-limit
                    placeholder="必填，写入重放审计记录" />
        </el-form-item>
        <el-form-item label="确认串" required>
          <el-input v-model.trim="confirmText" :placeholder="expectedConfirm" />
          <div class="hint">必须与要求完全一致：{{ expectedConfirm }}</div>
        </el-form-item>
        <el-form-item>
          <el-button
            type="danger"
            :loading="executing"
            :disabled="!preview.canExecute || !reason.trim() || confirmText !== expectedConfirm"
            @click="handleExecute"
          >
            执行链式重放
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="result"
        :title="`重放完成：${result.contestCount} 场 / ${result.affectedUsers} 名用户（记录 #${result.replayId}）`"
        type="success"
        :closable="false"
        show-icon
      />
    </el-card>
    <el-card class="mt-16" shadow="never" header="最近 50 次重放（备份仅管理员可读）">
      <el-table :data="history" size="small">
        <el-table-column prop="id" label="记录" width="80" />
        <el-table-column prop="fromContestId" label="起点比赛" width="100" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button link @click="downloadBackup(row.id)">保存备份</el-button>
            <el-button v-if="row.status === 'COMPLETED'" link type="danger" @click="handleRestore(row.id)">预览恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { executeSettlementReplay, previewSettlementReplay, getSettlementReplayHistory, getSettlementReplayBackup, previewSettlementRestore, restoreSettlementReplay } from '@/api/rejudge'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const executing = ref(false)
const preview = ref(null)
const result = ref(null)
const history = ref([])
const reason = ref('')
const confirmText = ref('')

const contestId = computed(() => Number(route.params.id))
const expectedConfirm = computed(() => preview.value?.confirmation || '')

function unwrap(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && 'data' in resp) return resp.data
  return resp
}
function assertOk(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && Number(resp.code) !== 200) {
    throw new Error(resp.message || resp.msg || '请求失败')
  }
  return unwrap(resp)
}
function formatTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

async function loadPreview() {
  if (!Number.isFinite(contestId.value) || contestId.value <= 0) {
    return ElMessage.error('比赛 ID 不合法')
  }
  loading.value = true
  result.value = null
  try {
    preview.value = assertOk(await previewSettlementReplay(contestId.value))
    confirmText.value = ''
    reason.value = ''
    history.value = assertOk(await getSettlementReplayHistory())
  } catch (e) {
    showApiError(e, e.message || '获取重放预览失败')
  } finally {
    loading.value = false
  }
}

async function downloadBackup(id) {
  try {
    const backup = assertOk(await getSettlementReplayBackup(id))
    const url = URL.createObjectURL(new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `settlement-replay-${id}.json`
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) { showApiError(e, e.message || '读取备份失败') }
}

async function handleRestore(id) {
  try {
    const check = assertOk(await previewSettlementRestore(id))
    if (!check.canExecute) return ElMessage.error(check.warnings.join('；'))
    const { value: restoreReason } = await ElMessageBox.prompt(
      `将恢复记录 #${id} 执行前的 ${check.contests.length} 场成绩、流水及 Rating。请填写恢复原因，并确认已有数据库备份。`,
      '恢复预览', { inputValidator: value => !!value?.trim() || '原因必填', inputPattern: /^.{1,500}$/, inputErrorMessage: '原因限 1~500 字' }
    )
    const { value: confirmation } = await ElMessageBox.prompt(`请输入确认串：${check.confirmation}`, '确认恢复')
    await restoreSettlementReplay(id, { reason: restoreReason, confirm: confirmation })
    ElMessage.success('恢复完成')
    await loadPreview()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') showApiError(e, e.message || '恢复失败')
  }
}

async function handleExecute() {
  try {
    await ElMessageBox.confirm(
      '链式重放会不可逆地重写历史榜单与 Rating，确认执行？',
      '确认链式重放',
      { type: 'error', confirmButtonText: '确认执行', cancelButtonText: '取消' }
    )
  } catch { return }
  executing.value = true
  try {
    result.value = assertOk(await executeSettlementReplay(contestId.value, {
      reason: reason.value.trim(),
      confirm: confirmText.value.trim()
    }))
    ElMessage.success('链式重放完成')
    await loadPreview()
  } catch (e) {
    showApiError(e, e.message || '执行链式重放失败')
  } finally {
    executing.value = false
  }
}

onMounted(loadPreview)
</script>

<style scoped>
.settlement-replay { padding: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.page-title { font-size: 22px; font-weight: 700; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.mb-16 { margin-bottom: 16px; }
.mt-16 { margin-top: 16px; }
.warn-list { margin: 6px 0 0; padding-left: 18px; }
.hint { margin-top: 6px; color: var(--text-secondary); font-size: 12px; }
</style>
