<template>
  <div class="report-detail-page admin-scroll-page" v-loading="loading">
    <el-card shadow="never" class="detail-card">
      <template v-if="report">
        <div class="card-title">{{ report.title || '举报详情' }}</div>

        <div class="user-row">
          <div class="user-box">
            <div class="user-label">作者</div>
            <div class="user-name">{{ report.reportedName || '未知用户' }}</div>
            <div v-if="report.reportedUsername" class="user-sub">@{{ report.reportedUsername }}</div>
            <div class="user-time">发布时间：{{ formatTime(report.postCreateTime) }}</div>
          </div>
          <div class="user-box align-right">
            <div class="user-label">举报人</div>
            <div class="user-name">{{ report.reporterName || '匿名' }}</div>
            <div v-if="report.reporterUsername" class="user-sub">@{{ report.reporterUsername }}</div>
            <div class="user-time">举报时间：{{ formatTime(report.createTime) }}</div>
          </div>
        </div>

        <div class="reason-block">
          <span class="reason-label">举报类型</span>
          <el-tag type="danger" effect="plain">{{ report.reason }}</el-tag>
        </div>

        <div class="detail-text">{{ report.detail || '（举报人未填写补充说明）' }}</div>

        <div class="action-bar">
          <el-button type="primary" plain @click="openPost">动态详情</el-button>
          <el-button @click="handleResolve(false)">忽略</el-button>
          <el-button type="danger" plain @click="handleResolve(true)">限制查看</el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
// 举报详情处理页（仅管理员）：处理单条内容举报，可选「限制查看」或「忽略举报」。
// 从社区治理列表以新标签页打开，处理完成后若有 opener 就直接关掉标签页退回列表，
// 否则（直接访问该 URL）退回治理列表。
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { getModerationReportDetail, resolveModerationReport } from '@/api/community'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const report = ref(null)

const formatTime = (value) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-')

const openPost = () => {
  if (!report.value?.postId) return
  const { href } = router.resolve({ name: 'PostDetail', params: { id: report.value.postId } })
  window.open(href, '_blank')
}

// 两种处置共用一个入口，用 hide 参数区分；处理说明必填提示是 prompt 的 placeholder，
// 因此允许留空（留空时按空字符串提交）。
const handleResolve = async (hide) => {
  try {
    const { value } = await ElMessageBox.prompt(
      hide ? '可填写处理说明（选填）' : '可填写忽略原因（选填）',
      hide ? '限制查看' : '忽略举报',
      { inputPlaceholder: '处理说明', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
    const res = await resolveModerationReport(report.value.id, { hide, note: value || '' })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '处理失败'))
      return
    }
    ElMessage.success(hide ? '已限制该动态可见' : '已忽略该举报')
    setTimeout(() => {
      if (window.opener) {
        window.close()
      } else {
        router.push('/admin/community')
      }
    }, 800)
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') console.error(e)
  }
}

onMounted(async () => {
  try {
    const res = await getModerationReportDetail(route.params.id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '加载举报详情失败'))
      return
    }
    report.value = unwrapResultData(res)
  } catch {
    ElMessage.error('加载举报详情失败')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.report-detail-page { padding: 16px; background: var(--bg-surface-hover); }
.detail-card {
  max-width: 960px;
  margin: 0 auto;
  border: 1px solid var(--border-light);
  border-radius: 14px;
}
.card-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-fill-color-light);
  margin-bottom: 20px;
}
.user-row { display: flex; justify-content: space-between; gap: 24px; flex-wrap: wrap; }
.user-box { min-width: 220px; }
.user-box.align-right { text-align: right; }
.user-label { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.user-name { font-size: 16px; font-weight: 600; color: var(--text-primary); }
.user-sub { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
.user-time { font-size: 12px; color: var(--text-secondary); margin-top: 8px; }
.reason-block { display: flex; align-items: center; gap: 10px; margin: 24px 0 16px; }
.reason-label { font-size: 13px; color: var(--text-regular); }
.detail-text {
  background: var(--bg-app);
  border: 1px solid var(--border-light);
  border-radius: 10px;
  padding: 18px 20px;
  min-height: 220px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
.action-bar { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }
</style>
