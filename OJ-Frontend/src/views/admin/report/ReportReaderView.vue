<template>
  <div class="reader-page">
    <header class="reader-header">
      <div class="header-left">
        <el-button plain @click="backToWorkspace">返回工作区</el-button>
        <div class="header-title">
          <div class="title-line">
            <span class="title-text">{{ job?.studentName || `报告 #${route.params.jobId}` }}</span>
            <el-tag v-if="job" :type="metaOf(STATUS_META, job.status).type" size="small">
              {{ metaOf(STATUS_META, job.status).label }}
            </el-tag>
          </div>
          <div class="title-meta">
            <span v-if="job">{{ job.periodStart }} ~ {{ job.periodEnd }}</span>
            <span v-if="job?.className">班级 {{ job.className }}</span>
            <span v-if="job">任务 #{{ job.id }}</span>
          </div>
        </div>
      </div>

      <div class="header-right">
        <el-button :disabled="!previousId" @click="goTo(previousId)">上一份</el-button>
        <el-button :disabled="!nextId" @click="goTo(nextId)">下一份</el-button>
        <el-radio-group v-model="view" :disabled="!hasHtml" class="view-switch">
          <el-radio-button value="structured">网页阅读</el-radio-button>
          <el-radio-button value="raw">A4 原始版</el-radio-button>
        </el-radio-group>
        <el-button :disabled="!hasHtml" :loading="exporting" @click="handleExportPng">导出 PNG</el-button>
        <el-button :disabled="!hasHtml" @click="handleDownloadHtml">另存为 HTML</el-button>
        <el-button type="primary" :disabled="!hasHtml" @click="handlePrint">打印</el-button>
      </div>
    </header>

    <div class="reader-content">
      <el-alert v-if="!structuredData && hasHtml" type="info" :closable="false" show-icon class="reader-alert">
        <template #title>当前报告未包含结构化数据，已使用 A4 原始版</template>
        <template #default>
          历史报告与运行中的旧版后端生成的报告没有结构化数据节点；重新生成后可获得网页阅读视图。原始版仍是打印与下载的权威版本。
        </template>
      </el-alert>

      <div v-if="loading" v-loading="true" class="state-box">
        <el-empty description="正在加载报告任务" :image-size="80" />
      </div>

      <el-result
        v-else-if="forbidden"
        icon="warning"
        title="无权查看该报告"
        :sub-title="error || '仅本班班主任或管理员可查看报告正文'"
        class="state-box"
      >
        <template #extra><el-button @click="backToWorkspace">返回工作区</el-button></template>
      </el-result>

      <el-result
        v-else-if="error"
        icon="error"
        title="报告详情加载失败"
        :sub-title="error"
        class="state-box"
      >
        <template #extra><el-button type="primary" @click="reload">重新加载</el-button></template>
      </el-result>

      <div v-else-if="!job" class="state-box">
        <el-empty description="报告任务不存在" :image-size="80" />
      </div>

      <div v-else-if="job.status === 'FAILED'" class="state-box">
        <el-result icon="error" title="报告生成失败" :sub-title="failureMessage(job)">
          <template #extra>
            <el-button v-if="canRetry(job)" type="warning" :loading="retrying" @click="handleRetry">重试生成</el-button>
            <el-button @click="backToWorkspace">返回工作区</el-button>
          </template>
        </el-result>
        <div class="failure-extra">
          <span v-if="failureCode(job)">错误码：{{ failureCode(job) }}</span>
          <span>已消耗 {{ job.attempts ?? 0 }} / {{ MAX_ATTEMPTS }} 次生成预算。</span>
          <span v-if="pollPausedMessage">{{ pollPausedMessage }}</span>
        </div>
      </div>

      <div v-else-if="inProgress" class="state-box">
        <el-result icon="info" :title="job.status === 'PENDING' ? '报告已受理，正在排队' : '报告正在后台生成'">
          <template #sub-title>
            页面每 2 秒自动刷新当前任务状态；关闭本页只会停止刷新，不会取消已受理任务。
          </template>
          <template #extra>
            <el-button v-if="pollPausedMessage" type="primary" @click="resume">继续刷新</el-button>
            <el-button @click="backToWorkspace">返回工作区</el-button>
          </template>
        </el-result>
        <div class="progress-meta">
          尝试 {{ job.attempts ?? 0 }} / {{ MAX_ATTEMPTS }} 次
          <span v-if="job.nextAttemptAt"> · 下次尝试 {{ formatTime(job.nextAttemptAt) }}</span>
        </div>
      </div>

      <el-result
        v-else-if="!hasHtml"
        icon="warning"
        title="报告正文未返回"
        sub-title="任务已终态但没有 HTML，请重新加载或返回工作区重试"
        class="state-box"
      >
        <template #extra><el-button type="primary" @click="reload">重新加载</el-button></template>
      </el-result>

      <template v-else>
        <!-- v-if 而非 v-show：无结构化数据时不得挂载组件（模板会读取 data.header）。
             v-show 只控制显示，组件仍会创建并抛空指针，连带影响原始版渲染。 -->
        <template v-if="structuredData">
          <div v-show="view === 'structured'">
            <ReportStructuredView :data="structuredData" />
          </div>
        </template>
        <div v-show="view === 'raw' || !structuredData">
          <ReportRawView ref="rawViewRef" :html="job.html" />
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
// 学习周报全屏阅读页（方案 B）。脱离管理侧栏，可在浏览器新标签页独立打开、
// 分享 URL 并排对比。原始版 iframe 常驻，保证打印与导出始终走权威 HTML。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { listReportJobs, retryReportJob } from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import { useReportExport } from '@/views/admin/class/composables/useReportExport'
import { useReportJob } from './composables/useReportJob'
import { extractReportData } from './composables/reportData'
import {
  MAX_ATTEMPTS, STATUS_META, canRetry, failureCode, failureMessage, metaOf
} from './composables/reportMeta'
import ReportStructuredView from './components/ReportStructuredView.vue'
import ReportRawView from './components/ReportRawView.vue'

const route = useRoute()
const router = useRouter()
const { exporting, exportImage } = useReportExport()

const view = ref('structured')
const rawViewRef = ref(null)
const retrying = ref(false)
const neighbors = ref([])

const {
  job, loading, error, forbidden, hasHtml, inProgress, pollPausedMessage,
  load, reload, apply, resume
} = useReportJob({ onTerminal: (data) => loadNeighbors(data?.classId) })

const structuredData = computed(() => extractReportData(job.value?.html))

const neighborIds = computed(() => neighbors.value.map((item) => Number(item.id)))
const currentIndex = computed(() => neighborIds.value.indexOf(Number(job.value?.id)))
const previousId = computed(() => currentIndex.value > 0 ? neighborIds.value[currentIndex.value - 1] : null)
const nextId = computed(() =>
  currentIndex.value >= 0 && currentIndex.value < neighborIds.value.length - 1
    ? neighborIds.value[currentIndex.value + 1]
    : null
)

function formatTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

function backToWorkspace() {
  router.push({ name: 'AdminReportCenter', query: { tab: 'history' } })
}

function goTo(id) {
  if (!id) return
  router.push({ name: 'AdminWeeklyReportReader', params: { jobId: id } })
}

async function loadNeighbors(classId) {
  const id = Number(classId)
  if (!Number.isInteger(id) || id <= 0) {
    neighbors.value = []
    return
  }
  try {
    const data = unwrapResultData(await listReportJobs({ classId: id, page: 1, size: 100 })) || {}
    neighbors.value = Array.isArray(data.records) ? data.records : []
  } catch {
    neighbors.value = []
  }
}

// 无结构化数据时自动落到原始版；有数据时尊重用户上次选择。
watch(structuredData, (value) => {
  if (!value) view.value = 'raw'
  else if (view.value === 'raw') view.value = 'structured'
}, { immediate: true })

watch(() => route.params.jobId, (value) => {
  if (!value) return
  view.value = 'structured'
  load(value)
}, { immediate: true })

function handlePrint() {
  rawViewRef.value?.print()
}

function handleExportPng() {
  if (!hasHtml.value) return
  exportImage(job.value.html, job.value.studentName || `学生 #${job.value.studentId}`)
}

function safeFileSegment(value) {
  return String(value || '学生').replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 40) || '学生'
}

function handleDownloadHtml() {
  if (!hasHtml.value) return
  const name = safeFileSegment(job.value.studentName || `学生 #${job.value.studentId}`)
  const fileName = `${name}_学习周报_${job.value.periodStart}_${job.value.periodEnd}.html`
  const blob = new Blob([job.value.html], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
  ElMessage.success('HTML 已开始下载')
}

async function handleRetry() {
  if (!job.value?.id || retrying.value) return
  retrying.value = true
  try {
    const updated = unwrapResultData(await retryReportJob(job.value.id))
    if (updated) {
      apply(updated)
      if (updated.status === 'GENERATED') ElMessage.success('报告已生成')
      else ElMessage.success('已重新排队，报告将由后台生成')
    }
  } catch {
    /* 全局请求拦截器已统一提示错误 */
  } finally {
    retrying.value = false
  }
}

onMounted(() => {
  if (job.value?.classId) loadNeighbors(job.value.classId)
})
</script>

<style scoped>
.reader-page { min-height: 100vh; background: var(--bg-app); color: var(--text-primary); }
.reader-header {
  position: sticky; top: 0; z-index: 20;
  display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap;
  background: var(--bg-surface); border-bottom: 1px solid var(--border-light);
  padding: 12px 24px;
}
.header-left, .header-right { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.header-title { margin-left: 6px; }
.title-line { display: flex; align-items: center; gap: 10px; }
.title-text { font-size: 17px; font-weight: 600; color: var(--text-primary); }
.title-meta { display: flex; gap: 14px; margin-top: 2px; font-size: 12px; color: var(--text-secondary); }
.view-switch { margin: 0 4px; }
.reader-content { min-height: calc(100vh - 80px); }
.reader-alert { max-width: 1240px; margin: 16px auto 0; }
.state-box { max-width: 720px; margin: 48px auto; padding: 24px; background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-md); }
.failure-extra, .progress-meta { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px 16px; font-size: 13px; color: var(--text-secondary); }
</style>
