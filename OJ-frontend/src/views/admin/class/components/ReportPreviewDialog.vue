<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="90%"
    top="3vh"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <!-- 学生切换栏 -->
    <div v-if="students.length > 1" class="student-tabs">
      <el-tag
        v-for="s in students"
        :key="s.studentId"
        :type="s.studentId === currentStudentId ? 'primary' : 'info'"
        :effect="s.studentId === currentStudentId ? 'dark' : 'plain'"
        class="student-tab"
        @click="switchStudent(s.studentId)"
      >
        {{ s.name }}
        <el-icon v-if="s.loading" class="is-loading"><Loading /></el-icon>
        <el-icon v-else-if="s.loaded && s.success"><CircleCheckFilled /></el-icon>
        <el-icon v-else-if="s.loaded && !s.success"><CircleCloseFilled /></el-icon>
      </el-tag>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-box">
      <el-icon class="is-loading" :size="32"><Loading /></el-icon>
      <p>正在生成报告...</p>
    </div>

    <!-- 生成失败 -->
    <div v-else-if="errorMsg" class="error-box">
      <el-result icon="error" :title="'报告生成失败'" :sub-title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="retry">重新生成</el-button>
        </template>
      </el-result>
    </div>

    <!-- 报告 iframe -->
    <div v-else-if="htmlContent" class="report-frame-wrapper">
      <iframe
        ref="reportIframeRef"
        :srcdoc="htmlContent"
        class="report-iframe"
        title="学生周报预览"
      />
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visible = false">关闭</el-button>
        <el-button
          v-if="htmlContent"
          type="success"
          :loading="exporting"
          @click="onExport"
        >
          导出图片
        </el-button>
        <el-button
          v-if="currentStudent && !currentStudent.loaded"
          type="primary"
          :loading="loading"
          @click="loadCurrent"
        >
          生成报告
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
// 学习周报预览弹窗：教师查看或生成单个学生的学习周报，并可导出 PNG。
//
// 报告生成是按需触发的（点「生成报告」才调接口），因为一次批量生成会为全班每人各跑一次
// 统计与图表渲染，代价高。已生成的结果缓存在 students 数组里，切换学生时直接复用不重算。
// PNG 导出走 useReportExport（隐藏 iframe + html2canvas 截图）。
import { ref, computed } from 'vue'
import { Loading, CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { generateBatchReports } from '@/api/report'
import { useReportExport } from '../composables/useReportExport'

const props = defineProps({
  /** 班级 ID */
  classId: { type: [String, Number], required: true }
})

const visible = ref(false)
const loading = ref(false)
const errorMsg = ref('')
const htmlContent = ref('')
const currentStudentId = ref(null)
const reportIframeRef = ref(null)

// 学生状态列表：[{ studentId, name, loaded, loading, success, html, error }]
const students = ref([])

// 报告导出（html2canvas iframe 截图）
const { exporting, exportImage } = useReportExport()
const onExport = () => exportImage(htmlContent.value, currentStudent.value?.name)

const currentStudent = computed(() =>
  students.value.find(s => s.studentId === currentStudentId.value)
)

const dialogTitle = computed(() => {
  const s = currentStudent.value
  return s ? `${s.name} · 学习周报` : '学习周报'
})

// 切换学生只做"取缓存"：loaded 为 true 直接用已有 html，
// 否则显示未生成态让用户手动触发，避免一次性为整班人生成。
function switchStudent(studentId) {
  currentStudentId.value = studentId
  const s = currentStudent.value
  if (!s) return
  if (s.loaded && s.success) {
    errorMsg.value = ''
    htmlContent.value = s.html
    loading.value = false
  } else if (s.loaded && !s.success) {
    htmlContent.value = ''
    errorMsg.value = s.error || '生成失败'
    loading.value = false
  } else {
    htmlContent.value = ''
    errorMsg.value = ''
    loading.value = false
  }
}

function loadCurrent() {
  const s = currentStudent.value
  if (!s) return
  loadStudent(s)
}

// 生成是按学生逐个调用的（studentIds 只传一个人），
// 而不是复用批量接口一次传全班——那样会让教师等待整班渲染完成才能看到第一个人的报告。
async function loadStudent(s) {
  s.loading = true
  loading.value = true
  errorMsg.value = ''
  htmlContent.value = ''
  try {
    const res = await generateBatchReports({
      classId: props.classId,
      studentIds: [s.studentId]
    })
    if (res.code === 200 && res.data?.length > 0) {
      const item = res.data[0]
      s.loaded = true
      s.success = item.success
      // 结果写回前先判断是否仍是当前选中学生：生成是异步的，
// 教师可能在等待期间切走，不加这个判断会把 A 的报告渲染到 B 的页面上。
      if (item.success && item.html) {
        s.html = item.html
        if (s.studentId === currentStudentId.value) {
          htmlContent.value = item.html
          errorMsg.value = ''
        }
      } else {
        s.error = item.error || '生成失败'
        if (s.studentId === currentStudentId.value) {
          errorMsg.value = s.error
        }
      }
    } else {
      s.loaded = true
      s.success = false
      s.error = '接口返回异常'
      if (s.studentId === currentStudentId.value) {
        errorMsg.value = s.error
      }
    }
  } catch (e) {
    s.loaded = true
    s.success = false
    s.error = e?.message || '网络异常'
    if (s.studentId === currentStudentId.value) {
      errorMsg.value = s.error
    }
  } finally {
    s.loading = false
    loading.value = false
  }
}

// 重试前清空 loaded：否则模板会走"已加载成功"分支把空 html 塞进 iframe。
function retry() {
  const s = currentStudent.value
  if (!s) return
  s.loaded = false
  s.success = false
  s.html = ''
  s.error = ''
  loadStudent(s)
}

/**
 * 打开对话框，批量加载指定学生列表。
 * @param {{ studentId: number, name: string }[]} studentList
 */
function open(studentList) {
  if (!studentList || studentList.length === 0) return
  students.value = studentList.map(s => ({
    studentId: s.studentId,
    name: s.name || `学生 ${s.studentId}`,
    loaded: false,
    loading: false,
    success: false,
    html: '',
    error: ''
  }))
  currentStudentId.value = studentList[0].studentId
  visible.value = true
  // 自动为第一个学生加载
  loadStudent(students.value[0])
}

/** 打开对话框并传入已生成的报告数据（批量生成后直接预览） */
function openWithResults(classId, batchResults, studentNameMap) {
  students.value = batchResults
    .filter(r => r.success)
    .map(r => ({
      studentId: r.studentId,
      name: studentNameMap?.[r.studentId] || `学生 ${r.studentId}`,
      loaded: true,
      loading: false,
      success: true,
      html: r.html,
      error: ''
    }))
  if (students.value.length === 0) return
  currentStudentId.value = students.value[0].studentId
  const first = students.value[0]
  htmlContent.value = first.html
  errorMsg.value = ''
  loading.value = false
  visible.value = true
}

defineExpose({ open, openWithResults })
</script>

<style scoped>
.student-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-light);
}

.student-tab {
  cursor: pointer;
  user-select: none;
}

.student-tab:hover {
  opacity: 0.85;
}

.loading-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 60vh;
  color: var(--text-secondary);
  gap: 12px;
}

.error-box {
  display: flex;
  justify-content: center;
  padding: 40px 0;
}

.report-frame-wrapper {
  width: 100%;
  height: 78vh;
  border: 1px solid var(--border-base);
  border-radius: 6px;
  overflow: hidden;
}

.report-iframe {
  width: 100%;
  height: 100%;
  border: none;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
