<template>
  <div class="page-container admin-scroll-page">
    <div class="page-header">
      <div class="page-title">🔍 查重报告</div>
      <div class="page-desc">班级内 / 比赛内同题提交两两比对（M1 班级 · M2 比赛 · AST 结构指纹优先，TOKEN 兜底）</div>
    </div>

    <!-- 筛选区：模式（班级/比赛）+ 班级/比赛 + 题目 + 触发查重 -->
    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-radio-group v-model="mode" size="default" @change="onModeChange">
          <el-radio-button value="class">班级查重</el-radio-button>
          <el-radio-button value="contest">比赛查重</el-radio-button>
        </el-radio-group>
        <template v-if="mode === 'class'">
          <el-select
            v-model="filters.classId"
            placeholder="选择班级"
            clearable
            filterable
            style="width: 220px"
            @change="onClassChange"
          >
            <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-select
            v-model="filters.problemId"
            placeholder="选择题目"
            clearable
            filterable
            style="width: 280px"
            :disabled="!filters.classId"
          >
            <el-option v-for="p in problems" :key="p.id" :label="`${p.id} · ${p.title}`" :value="p.id" />
          </el-select>
          <el-button type="primary" :loading="running" :disabled="!filters.classId || !filters.problemId" @click="runCheck">
            开始查重
          </el-button>
        </template>
        <template v-else>
          <el-select
            v-model="filters.contestId"
            placeholder="选择比赛"
            clearable
            filterable
            style="width: 320px"
            @change="onContestChange"
          >
            <el-option v-for="c in contests" :key="c.id" :label="c.title" :value="c.id" />
          </el-select>
          <el-button type="primary" :loading="running" :disabled="!filters.contestId" @click="runContestCheck">
            开始查重
          </el-button>
        </template>
        <el-radio-group v-model="filters.status" size="default" @change="loadReports">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="PENDING">疑似</el-radio-button>
          <el-radio-button value="REVIEWED">已核实</el-radio-button>
          <el-radio-button value="IGNORED">已忽略</el-radio-button>
        </el-radio-group>
      </div>
    </el-card>

    <!-- 报告列表 -->
    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="reports" stripe>
        <el-table-column prop="userAName" label="学生 A" min-width="110" show-overflow-tooltip />
        <el-table-column prop="userBName" label="学生 B" min-width="110" show-overflow-tooltip />
        <el-table-column label="范围" width="80">
          <template #default="{ row }">
            <el-tag :type="row.contestId ? 'success' : ''" size="small" effect="plain">
              {{ row.contestId ? '比赛' : '班级' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="相似度" width="200">
          <template #default="{ row }">
            <div class="sim-cell">
              <el-progress
                :percentage="Math.round(row.similarity * 100)"
                :color="similarityColor(row.similarity)"
                :stroke-width="10"
              />
              <span class="sim-num">{{ (row.similarity * 100).toFixed(1) }}%</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.similarity >= 0.85" type="danger" size="small">疑似</el-tag>
            <el-tag v-else type="warning" size="small">关注</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="方法" width="80">
          <template #default="{ row }">
            <el-tag :type="row.method === 'AST' ? '' : 'info'" size="small" effect="plain">
              {{ row.method }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="生成时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openCompare(row)">
              对比代码
            </el-button>
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="success" link @click="markStatus(row, 'REVIEWED')">核实</el-button>
              <el-button size="small" type="info" link @click="markStatus(row, 'IGNORED')">忽略</el-button>
            </template>
            <template v-else-if="row.status === 'REVIEWED'">
              <el-button size="small" type="info" link @click="markStatus(row, 'IGNORED')">忽略</el-button>
            </template>
            <template v-else>
              <el-button size="small" type="success" link @click="markStatus(row, 'REVIEWED')">核实</el-button>
            </template>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="mode === 'class' ? '暂无查重报告，请选择班级与题目后点击「开始查重」' : '暂无查重报告，请选择比赛后点击「开始查重」'"
            :image-size="70"
          />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="loadReports"
          @size-change="loadReports"
        />
      </div>
    </el-card>

    <!-- 代码对比弹窗 -->
    <el-dialog v-model="compareVisible" title="代码对比" width="92%" top="4vh" destroy-on-close>
      <div v-if="compareLoading" v-loading="true" class="compare-loading" />
      <div v-else class="compare-grid">
        <div class="compare-col">
          <div class="compare-head">{{ compareA?.username || `提交 #${compareA?.submissionId}` }}</div>
          <pre class="code-view"><code>{{ compareA?.code || '（无代码）' }}</code></pre>
        </div>
        <div class="compare-col">
          <div class="compare-head">{{ compareB?.username || `提交 #${compareB?.submissionId}` }}</div>
          <pre class="code-view"><code>{{ compareB?.code || '（无代码）' }}</code></pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
// 代码查重（教师工具）：在「班级 × 题目」或「比赛」范围内两两比对提交代码，
// 产出疑似抄袭报告供教师人工核实。
//
// 相似度只作为线索而非结论——模板相同的正常解法也会高分相似，
// 最终认定必须由教师看代码对比后手动标记状态。
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getMyClasses } from '@/api/class'
import { getProblemList } from '@/api/problem'
import { getContestList } from '@/api/contest'
import { runPlagiarismCheck, runContestPlagiarismCheck, getPlagiarismReports, getPlagiarismCodes, updatePlagiarismStatus } from '@/api/plagiarism'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'

const mode = ref('class')
const classes = ref([])
const contests = ref([])
const problems = ref([])
const reports = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const running = ref(false)

const filters = reactive({
  classId: null,
  contestId: null,
  problemId: null,
  status: ''
})

const compareVisible = ref(false)
const compareLoading = ref(false)
const compareA = ref(null)
const compareB = ref(null)

onMounted(async () => {
  await loadClasses()
  loadReports()
})

async function loadClasses() {
  const res = await getMyClasses({ page: 1, size: 200 })
  if (isResultSuccess(res)) {
    classes.value = unwrapResultData(res)?.records || []
  } else {
    ElMessage.warning(getResultMessage(res, '班级列表加载失败'))
  }
}

// 切换班级要重置已选题目的题目：题目列表是全量的（未按班级过滤），
// 不清空会让用户把上一个班级选的题目带过来查错班级。
async function onClassChange() {
  filters.problemId = null
  problems.value = []
  if (!filters.classId) {
    loadReports()
    return
  }
  const res = await getProblemList({ page: 1, size: 200 })
  if (isResultSuccess(res)) {
    problems.value = unwrapResultData(res)?.records || []
  }
}

// 两种查重范围的数据结构不同（班级要 classId+problemId，比赛只要 contestId），
// 切换时把两套筛选条件一起清空，避免带着上一个模式的残留参数请求。
async function onModeChange() {
  filters.classId = null
  filters.contestId = null
  filters.problemId = null
  page.value = 1
  if (mode.value === 'contest' && contests.value.length === 0) {
    await loadContests()
  }
  loadReports()
}

async function loadContests() {
  const res = await getContestList({ currentPage: 1, pageSize: 200 })
  if (isResultSuccess(res)) {
    contests.value = unwrapResultData(res)?.records || []
  } else {
    ElMessage.warning(getResultMessage(res, '比赛列表加载失败'))
  }
}

async function onContestChange() {
  page.value = 1
  loadReports()
}

// 查重是两两比对，复杂度随提交量平方增长，耗时可能很长；
// running 同时作为按钮禁用态，避免重复触发同一批任务。
async function runCheck() {
  running.value = true
  try {
    const res = await runPlagiarismCheck({ classId: filters.classId, problemId: filters.problemId })
    if (isResultSuccess(res)) {
      const count = unwrapResultData(res)
      ElMessage.success(count > 0 ? `查重完成，新增 ${count} 条报告` : '查重完成，无新增报告（重复对已跳过）')
      loadReports()
    } else {
      ElMessage.error(getResultMessage(res, '查重失败'))
    }
  } finally {
    running.value = false
  }
}

async function runContestCheck() {
  running.value = true
  try {
    const res = await runContestPlagiarismCheck(filters.contestId)
    if (isResultSuccess(res)) {
      const count = unwrapResultData(res)
      ElMessage.success(count > 0 ? `查重完成，新增 ${count} 条报告` : '查重完成，无新增报告（重复对已跳过）')
      loadReports()
    } else {
      ElMessage.error(getResultMessage(res, '查重失败'))
    }
  } finally {
    running.value = false
  }
}

// 按当前模式组装参数：不相关的字段显式置 undefined 而不是删掉，
// 避免上一次查询的条件被后端当成"未传"从而放宽过滤。
async function loadReports() {
  loading.value = true
  try {
    const res = await getPlagiarismReports({
      classId: mode.value === 'class' ? (filters.classId || undefined) : undefined,
      contestId: mode.value === 'contest' ? (filters.contestId || undefined) : undefined,
      problemId: filters.problemId || undefined,
      status: filters.status || undefined,
      page: page.value,
      size: pageSize.value
    })
    if (isResultSuccess(res)) {
      const data = unwrapResultData(res) || {}
      reports.value = data.records || []
      total.value = data.total || 0
    }
  } finally {
    loading.value = false
  }
}

// 报告里只存 submissionId，代码要另取——查重表会长期保留，
// 内嵌完整代码会让表体积暴涨。查两份代码用一次批量请求。
async function openCompare(row) {
  compareVisible.value = true
  compareLoading.value = true
  compareA.value = null
  compareB.value = null
  try {
    const res = await getPlagiarismCodes([row.submissionAId, row.submissionBId])
    if (isResultSuccess(res)) {
      const list = unwrapResultData(res) || []
      compareA.value = list.find((s) => s.submissionId === row.submissionAId) || null
      compareB.value = list.find((s) => s.submissionId === row.submissionBId) || null
    } else {
      ElMessage.error(getResultMessage(res, '代码加载失败'))
      compareVisible.value = false
    }
  } finally {
    compareLoading.value = false
  }
}

async function markStatus(row, status) {
  const label = status === 'REVIEWED' ? '已核实' : '已忽略'
  const res = await updatePlagiarismStatus(row.id, status)
  if (isResultSuccess(res)) {
    ElMessage.success(`已标记为${label}`)
    loadReports()
  } else {
    ElMessage.error(getResultMessage(res, '状态更新失败'))
  }
}

// 三档阈值：>=0.85 基本可判定为抄袭，>=0.7 需要人工看一眼，
// 其余多半是模板或写法相近。阈值集中在这里，改判定口径只需动这一处。
function similarityColor(sim) {
  if (sim >= 0.85) return 'var(--accent-danger)'
  if (sim >= 0.7) return 'var(--accent-warning)'
  return 'var(--accent-success)'
}

function statusType(status) {
  return { PENDING: 'warning', REVIEWED: 'success', IGNORED: 'info' }[status] || 'info'
}

function statusLabel(status) {
  return { PENDING: '待核实', REVIEWED: '已核实', IGNORED: '已忽略' }[status] || status
}

function fmtTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
/* 页面容器：与管理页统一规范（padding 16px + 居中限宽），修复紧贴边缘问题 */
.page-container {
  padding: 16px;
  margin: 0 auto;
  box-sizing: border-box;
  max-width: 1600px;
  width: 100%;
}
.page-header { margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.page-desc { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
.filter-card { margin-bottom: 16px; }
.filter-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.filter-row :deep(.el-radio-group:last-of-type) { margin-left: auto; }
.table-card :deep(.el-card__body) { padding-top: 4px; }
.sim-cell { display: flex; align-items: center; gap: 8px; }
.sim-num { font-size: 12px; color: var(--text-regular); min-width: 42px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.compare-loading { height: 320px; }
.compare-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.compare-col { min-width: 0; }
.compare-head {
  padding: 6px 10px; font-size: 13px; font-weight: 600;
  background: var(--bg-surface-hover); border: 1px solid var(--border-light); border-bottom: none;
  border-radius: 6px 6px 0 0;
  color: var(--text-primary);
}
.code-view {
  --plag-code-bg: #282c34; /* 代码块固定深底（编辑器惯例），暗色主题局部覆盖 */
  --plag-code-fg: #d4d4d4;
  margin: 0; padding: 12px; max-height: 60vh; overflow: auto;
  background: var(--plag-code-bg); color: var(--plag-code-fg);
  border-radius: 0 0 6px 6px;
  font-family: var(--font-mono); font-size: 13px; line-height: 1.6;
  white-space: pre; word-break: break-all;
}
.dark .code-view {
  --plag-code-bg: #161b22;
  --plag-code-fg: #e6edf3;
}
</style>
