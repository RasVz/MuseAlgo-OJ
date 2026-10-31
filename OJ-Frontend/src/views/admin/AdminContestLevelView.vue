<template>
  <div class="admin-contest-levels admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">比赛等级</div>
        <div class="page-desc">
          配置正式赛的参赛 Rating 范围（下限含、上限不含；留空表示该侧不限）。比赛创建/更新时快照到比赛，改配置不追溯历史。
        </div>
      </div>
      <div class="page-actions">
        <el-button :loading="loading" @click="fetchList">刷新</el-button>
        <el-button type="primary" @click="openCreate">新增等级</el-button>
      </div>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="levels" border stripe height="100%" empty-text="暂无比赛等级配置">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="code" label="等级编码" width="150" />
        <el-table-column prop="name" label="等级名称" min-width="140" />
        <el-table-column label="Rating 范围" min-width="220">
          <template #default="{ row }">
            <span class="rating-range">{{ formatRatingRange(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled === false ? 'info' : 'success'" size="small">
              {{ row.enabled === false ? '停用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
        <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑比赛等级' : '新增比赛等级'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="等级编码" prop="code">
          <el-input v-model.trim="form.code" placeholder="唯一编码，如 BASIC / ADVANCED" maxlength="32" />
        </el-form-item>
        <el-form-item label="等级名称" prop="name">
          <el-input v-model.trim="form.name" placeholder="展示名称，如 基础赛 / 进阶赛" maxlength="64" />
        </el-form-item>
        <el-form-item label="Rating 下限" prop="minRating">
          <div class="rating-field">
            <el-input-number v-model="form.minRating" :precision="0" :step="50" placeholder="不限" />
            <span class="field-hint">含</span>
          </div>
        </el-form-item>
        <el-form-item label="Rating 上限" prop="maxRating">
          <div class="rating-field">
            <el-input-number v-model="form.maxRating" :precision="0" :step="50" placeholder="不限" />
            <span class="field-hint">不含（必须大于下限）</span>
          </div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :precision="0" :min="0" :step="1" />
          <span class="field-hint">数字越小越靠前</span>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model.trim="form.description" type="textarea" :rows="3" placeholder="可选，等级说明" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 比赛等级配置（仅管理员，T10-1）：维护「等级 → Rating 范围」映射。
// 下限含、上限不含；任一侧留空表示不限。比赛创建时会把等级范围快照写入比赛行，
// 因此这里修改配置只影响之后创建/更新的比赛，不追溯历史成绩。
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getContestLevelList,
  createContestLevel,
  updateContestLevel
} from '@/api/adminContestLevel'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

const loading = ref(false)
const saving = ref(false)
const levels = ref([])
const dialogVisible = ref(false)
const formRef = ref(null)

const form = reactive({
  id: null,
  code: '',
  name: '',
  minRating: null,
  maxRating: null,
  enabled: true,
  sortOrder: 0,
  description: ''
})

// 上限不含：min < max 才合法。两侧都填时需要校验；只填一侧表示该侧不限。
function validateRatingRange(_rule, _value, callback) {
  const min = form.minRating
  const max = form.maxRating
  if (min != null && max != null && Number(min) >= Number(max)) {
    callback(new Error('Rating 下限必须小于上限（上限不含）'))
    return
  }
  callback()
}

const rules = {
  code: [{ required: true, message: '请输入等级编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入等级名称', trigger: 'blur' }],
  minRating: [{ validator: validateRatingRange, trigger: 'change' }],
  maxRating: [{ validator: validateRatingRange, trigger: 'change' }]
}

function formatRatingRange(row) {
  const min = row?.minRating
  const max = row?.maxRating
  if (min == null && max == null) return '不限'
  if (min != null && max != null) return `${min} ≤ Rating < ${max}`
  if (min != null) return `Rating ≥ ${min}`
  return `Rating < ${max}`
}

async function fetchList() {
  loading.value = true
  try {
    const res = await getContestLevelList()
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '获取比赛等级失败'))
      levels.value = []
      return
    }
    const data = unwrapResultData(res)
    levels.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
    levels.value = []
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    id: null,
    code: '',
    name: '',
    minRating: null,
    maxRating: null,
    enabled: true,
    sortOrder: 0,
    description: ''
  })
}

// 弹窗内容按需渲染，打开后下一帧表单实例才存在；此时清空上一次的校验错误。
function clearValidateAfterOpen() {
  nextTick(() => formRef.value?.clearValidate())
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
  clearValidateAfterOpen()
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id ?? null,
    code: row.code ?? '',
    name: row.name ?? '',
    minRating: row.minRating ?? null,
    maxRating: row.maxRating ?? null,
    enabled: row.enabled !== false,
    sortOrder: row.sortOrder ?? 0,
    description: row.description ?? ''
  })
  dialogVisible.value = true
  clearValidateAfterOpen()
}

async function onSave() {
  if (!formRef.value) return

  try {
    await formRef.value.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const payload = {
      code: form.code,
      name: form.name,
      minRating: form.minRating,
      maxRating: form.maxRating,
      enabled: form.enabled,
      sortOrder: Number(form.sortOrder) || 0,
      description: form.description
    }

    const res = form.id
      ? await updateContestLevel(form.id, payload)
      : await createContestLevel(payload)

    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '保存失败'))
      return
    }

    ElMessage.success('保存成功')
    dialogVisible.value = false
    fetchList()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

onMounted(fetchList)
</script>

<style scoped>
.admin-contest-levels {
  padding: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.page-desc {
  color: var(--text-secondary);
  font-size: 13px;
  margin-top: 4px;
  max-width: 760px;
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.admin-contest-levels :deep(.el-card) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.admin-contest-levels :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.admin-contest-levels :deep(.el-table) {
  flex: 1;
  min-height: 0;
}

.rating-range {
  font-family: monospace;
  color: var(--text-regular);
}

.rating-field {
  display: flex;
  align-items: center;
  gap: 10px;
}

.field-hint {
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
