<template>
  <div class="panel-card" shadow="never">
    <div class="panel-title">测试用例管理</div>

    <el-alert
      title="可上传 Zip 包（.in/.out 一一对应）或使用 AI 生成测试数据；用例按顺序判题，删除后自动重排。"
      type="info"
      show-icon
      :closable="false"
      class="mb-4"
    />

    <div class="case-tools">
      <el-button type="primary" size="small" icon="MagicStick" :disabled="!problemId" @click="openAiDialog">
        AI 生成测试数据
      </el-button>
      <el-button size="small" type="danger" plain :disabled="!caseList.length" @click="handleClearAll">
        清空全部
      </el-button>
    </div>

    <el-upload
      class="upload-demo"
      drag
      action=""
      :http-request="handleUpload"
      :show-file-list="false"
      accept=".zip"
    >
      <el-icon class="el-icon--upload"><upload-filled /></el-icon>
      <div class="el-upload__text">
        拖拽 Zip 文件到此处，或 <em>点击上传</em>
      </div>
    </el-upload>

    <div class="case-list-header">
      <span>已存在的用例（{{ caseList.length }} 组）</span>
      <el-button link type="primary" size="small" @click="loadCases">刷新</el-button>
    </div>

    <el-table :data="caseList" border style="width: 100%" height="400" row-key="id">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="case-expand">
            <div class="expand-col">
              <div class="expand-title">输入</div>
              <pre class="expand-pre">{{ row.input || '（无内容）' }}</pre>
            </div>
            <div class="expand-col">
              <div class="expand-title">输出</div>
              <pre class="expand-pre">{{ row.output || '（无内容）' }}</pre>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="caseOrder" label="序号" width="70" align="center" />
      <el-table-column prop="score" label="分值" width="70" align="center" />
      <el-table-column label="输入预览" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.input || '-' }}</template>
      </el-table-column>
      <el-table-column label="输出预览" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.output || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <ProblemAiDialog v-model="aiDialogVisible" :problem-id="problemIdNum" mode="testdata" @saved="loadCases" />
  </div>
</template>

<script setup>
// 判题用例管理：上传 / 下载 / 删除用例，以及一次性清空。
// 用例是判题的唯一依据，删错一道题的用例会直接改变评测结果，
// 因此删除与清空都带二次确认；AI 生成用例走 ProblemAiDialog。
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { getProblemCases, uploadProblemCases, deleteProblemCase, clearProblemCases } from '@/api/problem'
import ProblemAiDialog from '@/views/teacher/tools/ProblemAiDialog.vue'

const props = defineProps({
  problemId: { type: [Number, String], default: null }
})

const caseList = ref([])
const aiDialogVisible = ref(false)
const problemIdNum = computed(() => (props.problemId ? Number(props.problemId) : null))

// 输入/输出字段名在不同来源（上传文件解析、AI 生成、旧数据）下不一致，
// 统一归一到 input/output，模板只认归一后的名字。
const loadCases = async () => {
  if (!props.problemId) return
  try {
    const res = await getProblemCases(props.problemId)
    if (res.code === 200) {
      const rawList = Array.isArray(res.data) ? res.data : []
      caseList.value = rawList.map(item => ({
        ...item,
        input: item.input ?? item.inputCase ?? '',
        output: item.output ?? item.outputCase ?? ''
      }))
    } else {
      caseList.value = []
      ElMessage.error(res.message || '加载测试用例失败')
    }
  } catch (e) {
    console.error(e)
    caseList.value = []
    ElMessage.error('加载测试用例失败')
  }
}

// AI 生成测试数据：先确认再打开生成器面板（消耗一次 AI 调用）
const openAiDialog = async () => {
  if (!props.problemId) return
  try {
    await ElMessageBox.confirm(
      '将调用 AI 生成 Python 生成器代码并展示（消耗一次 AI 调用）。' +
      '生成后可编辑生成器并执行验证，确认通过后再追加保存。',
      '确认 AI 生成测试数据',
      { type: 'warning', confirmButtonText: '生成', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  aiDialogVisible.value = true
}

// 用例必须挂在已保存的题目上——上传接口按 problemId 关联，
// 题目还没落库时先拦下来，避免用户以为上传成功了。
const handleUpload = async (options) => {
  if (!props.problemId) {
    ElMessage.warning('请先保存基本信息，再上传测试用例')
    return
  }

  const { file } = options
  const formData = new FormData()
  formData.append('file', file)
  formData.append('problemId', props.problemId)

  try {
    const res = await uploadProblemCases(formData)
    if (res.code === 200) {
      ElMessage.success(`导入成功：${res.data} 组用例`)
      loadCases()
    } else {
      ElMessage.error(res.message || '上传失败')
    }
  } catch {
    ElMessage.error('上传出错')
  }
}

// 删除单组用例同样会改变判题结果（剩余用例重排 + 分值重算），
// 确认文案里把这两个副作用说清楚，避免用户以为只是删掉一行数据。
const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除第 ${row.caseOrder ?? '-'} 组用例？删除后剩余用例会自动重排并重算分值。`,
      '删除测试用例',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  try {
    const res = await deleteProblemCase(row.id)
    if (res.code === 200) {
      ElMessage.success('测试用例已删除')
      loadCases()
    } else {
      ElMessage.error(res.message || '删除失败')
    }
  } catch {
    ElMessage.error('删除失败')
  }
}

// 清空是本题最不可逆的操作（连文件一起删），因此确认文案里给出用例数量。
const handleClearAll = async () => {
  if (!props.problemId || !caseList.value.length) return
  try {
    await ElMessageBox.confirm(
      `确定清空该题全部 ${caseList.value.length} 组测试用例？文件与记录将一并删除，清空后需重新生成。`,
      '清空全部测试用例',
      { type: 'warning', confirmButtonText: '清空全部', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  try {
    const res = await clearProblemCases(props.problemId)
    if (res.code === 200) {
      ElMessage.success('该题测试用例已全部清空')
      loadCases()
    } else {
      ElMessage.error(res.message || '清空失败')
    }
  } catch {
    ElMessage.error('清空失败')
  }
}

// problemId 变化（新建成功后拿到 ID，或切换题目）→ 重新拉用例。
// immediate 保证首次挂载也会加载。defineExpose 让父组件在保存基本信息后能主动刷新。
watch(
  () => props.problemId,
  (val) => {
    if (val) loadCases()
  },
  { immediate: true }
)

defineExpose({ loadCases })
</script>

<style scoped>
.panel-card {
  border-radius: 16px;
  border: 1px solid var(--border-light);
  background: var(--bg-surface);
  padding: 16px 18px;
}

.mb-4 {
  margin-bottom: 16px;
}

.panel-title {
  font-size: 16px;
  color: var(--text-primary);
  font-weight: 700;
  margin-bottom: 16px;
}

.case-tools {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}

.case-list-header {
  margin: 18px 0 10px;
  font-weight: 700;
  font-size: 14px;
  color: var(--text-primary);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.case-expand {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  padding: 4px 12px 8px;
}

.expand-title {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 6px;
  font-weight: 600;
}

.expand-pre {
  margin: 0;
  background: var(--bg-surface-soft);
  border: 1px solid var(--border-light);
  border-radius: 8px;
  padding: 10px 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 240px;
  overflow-y: auto;
}
</style>
