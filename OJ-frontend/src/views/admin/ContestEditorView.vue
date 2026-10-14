<template>
  <div class="contest-editor-page admin-scroll-page">
    <el-card shadow="never" class="header-card">
      <div class="header-top">
        <div>
          <div class="title">
            {{ pageTitle }}
          </div>
          <div class="desc">
            {{ pageDesc }}
          </div>
        </div>
        <div class="actions">
          <!-- <el-button @click="goBackList">返回比赛列表</el-button> -->
          <el-button v-if="isEditMode" @click="refreshAll">刷新</el-button>
          <el-button
            v-if="isEditMode && !isClassMode"
            :loading="rebuildingRank"
            @click="handleRebuildRank"
          >
            重建排行榜
          </el-button>
          <el-button
            v-if="isEditMode"
            type="danger"
            :loading="deleting"
            @click="handleDeleteContest"
          >
            删除比赛
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="form-card" v-loading="loadingDetail">
      <template #header>
        <div class="card-header">{{ isCreateMode ? '创建信息' : '比赛信息' }}</div>
      </template>

      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item label="比赛名称" prop="title">
          <el-input v-model.trim="form.title" placeholder="如：2025春季期中考试" />
        </el-form-item>

        <el-form-item label="比赛时间" required>
          <el-date-picker
            v-model="dateRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="是否公开" prop="visible">
          <el-radio-group v-model="form.visible">
            <el-radio :label="1">公开（可见）</el-radio>
            <el-radio :label="0">不公开（不可见）</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="赛制" prop="mode">
          <el-radio-group v-model="form.mode">
            <el-radio label="ACM">ACM（全对得分，实时可见）</el-radio>
            <el-radio label="OI">OI（按子任务给分，赛中只显示"已提交"）</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="比赛描述" prop="description">
          <el-input v-model.trim="form.description" type="textarea" :rows="4" placeholder="比赛规则、说明等" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">
            {{ isCreateMode ? '创建比赛' : '保存修改' }}
          </el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <template v-if="isEditMode">
      <ContestProblemManager
        :problems="problemList"
        :loading="loadingProblems"
        :adding="addingProblem"
        :saving-id="updatingProblemId"
        :toggling-id="togglingProblemId"
        :removing-id="removingProblemRelationId"
        @add="handleAddProblems"
        @refresh="loadProblemList"
        @save-config="handleSaveProblemConfig"
        @toggle-visible="handleToggleVisible"
        @remove="handleRemoveProblem"
        @reorder="handleReorder"
      />
    </template>
  </div>
</template>

<script setup>
// 比赛编辑器：创建/编辑比赛的基本信息与题目配置，仅管理员（班级比赛为教师）可访问。
//
// 本组件只做编排：表单交给 useContestForm、题目管理交给 useContestProblems 与
// ContestProblemManager，自身负责页面级联动（路由变化 → 重新初始化）、
// 重建实时榜与删除比赛两个危险动作。
// 比赛题目通过 problem_contest 关联表维护，order_no 决定赛题顺序。
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteContest, rebuildContestRank } from '@/api/contest'
import { getResultMessage, isResultSuccess } from '@/utils/response'
import ContestProblemManager from './components/ContestProblemManager.vue'
import { useContestForm } from './composables/useContestForm'
import { useContestProblems } from './composables/useContestProblems'

const route = useRoute()
const router = useRouter()

const {
  formRef, form, dateRange, rules,
  loadingDetail, saving,
  isCreateMode, isEditMode, contestId, classId, isClassMode, pageTitle, pageDesc,
  resetPageState, resetForm, loadContestDetail, saveContest
} = useContestForm()

const {
  problemList, loadingProblems, addingProblem,
  updatingProblemId, togglingProblemId, removingProblemRelationId,
  clearList, loadProblemList, handleAddProblems, handleSaveProblemConfig,
  handleToggleVisible, handleRemoveProblem, handleReorder
} = useContestProblems(contestId)

const deleting = ref(false)
const rebuildingRank = ref(false)

// 路由变化（创建↔编辑、切换班级比赛）→ 清空旧状态后重新加载。
// 必须重置而不是只重新请求，否则上一个比赛的题目会残留在列表里。
watch(
  () => [route.name, route.params.id, route.params.classId, route.query.classId],
  async () => {
    await initPage()
  },
  { immediate: true }
)

async function initPage() {
  resetPageState()
  clearList()

  if (isCreateMode.value) return
  if (!isEditMode.value || !contestId.value) return

  await loadContestDetail()
  await loadProblemList()
}

// 创建成功会拿到新 ID 并跳到编辑页；班级比赛不走这条路，直接回班级详情。
// 跳转动作放在这里而不是 useContestForm 内，是为了不让 composable 依赖 router。
async function handleSave() {
  const { ok, createdId } = await saveContest()
  if (!ok) return
  if (createdId) {
    router.push({ name: 'AdminContestEdit', params: { id: createdId } })
    return
  }
  if (isCreateMode.value) {
    goBackList()
  }
}

// 重建榜单会覆盖 Redis 中该比赛的实时榜缓存，用于 Redis 重启或判题积压导致榜单丢失后的补救。
// 覆盖式操作，先确认再执行；班级比赛不走全局榜单，故直接跳过。
async function handleRebuildRank() {
  if (!contestId.value || isClassMode.value) return

  try {
    await ElMessageBox.confirm(
      '确认根据当前比赛的提交记录重建 Redis 实时排行榜吗？这会覆盖该比赛现有的实时榜缓存。',
      '重建排行榜',
      {
        type: 'warning',
        confirmButtonText: '确认重建',
        cancelButtonText: '取消'
      }
    )

    rebuildingRank.value = true
    const res = await rebuildContestRank(contestId.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '重建排行榜失败'))
      return
    }

    ElMessage.success(getResultMessage(res, '重建排行榜成功'))
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.message || '重建排行榜失败')
    }
  } finally {
    rebuildingRank.value = false
  }
}

async function handleDeleteContest() {
  try {
    await ElMessageBox.confirm(`确定要删除比赛 #${contestId.value} 吗？此操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })

    deleting.value = true
    const res = await deleteContest(contestId.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '删除比赛失败'))
      return
    }

    ElMessage.success('删除比赛成功')
    await router.push({ name: 'AdminContest' })
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.message || '删除比赛失败')
    }
  } finally {
    deleting.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadContestDetail(), loadProblemList()])
}

function goBackList() {
  if (isClassMode.value && classId.value) {
    router.push({
      name: 'AdminClassDetail',
      params: { id: classId.value },
      query: { tab: 'contest' }
    })
    return
  }
  router.push({ name: 'AdminContest' })
}
</script>

<style scoped>
.contest-editor-page {
  max-width: 1400px;
  margin: 0 auto;
}

.header-card,
.form-card {
  margin-bottom: 16px;
}

.header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.title {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.desc {
  color: var(--text-secondary);
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.card-header {
  font-weight: 600;
  color: var(--text-primary);
}
</style>
