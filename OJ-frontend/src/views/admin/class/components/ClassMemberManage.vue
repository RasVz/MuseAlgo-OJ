<template>
  <div class="member-manager">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名或备注..."
          prefix-icon="Search"
          style="width: 260px"
          clearable
          @keyup.enter="fetchMembers"
          @clear="fetchMembers"
        />
        <el-button
          type="warning"
          plain
          :disabled="selectedMembers.length === 0"
          :loading="batchGenerating"
          @click="handleBatchGenerate"
        >
          <el-icon><Document /></el-icon>
          批量生成周报 ({{ selectedMembers.length }})
        </el-button>
      </div>
      <div class="right-info">
        <span class="info-text">当前成员数: {{ total }}</span>
        <el-button icon="Refresh" circle plain @click="fetchMembers" />
      </div>
    </div>

    <el-table
      ref="tableRef"
      :data="memberList"
      v-loading="loading"
      style="width: 100%"
      :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)' }"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="50" align="center" />
      <el-table-column label="学生信息" min-width="200">
        <template #default="{ row }">
          <div class="user-info-cell" @click="goToProfile(row.userId)">
            <el-avatar :size="40" :src="row.avatar" >{{ row.username?.charAt(0) }}</el-avatar>
            <div class="user-text">
              <div class="username">{{ row.username }}</div>
              <div class="email" v-if="row.email">{{ row.email }}</div>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="班级备注" min-width="150">
        <template #default="{ row }">
          <div class="remark-display" v-if="!row.editingRemark">
            <span v-if="row.remark" class="remark-text">{{ row.remark }}</span>
            <span v-else class="no-remark">未设置备注</span>
            <el-button link type="primary" icon="EditPen" class="edit-icon" @click="startEdit(row)" />
          </div>
          <div class="remark-edit" v-else>
            <el-input 
              v-model="row.tempRemark" 
              size="small" 
              placeholder="输入备注" 
              ref="remarkInputRef"
              @keyup.enter="saveRemark(row)"
            />
            <el-button link type="success" size="small" icon="Check" @click="saveRemark(row)" />
            <el-button link type="info" size="small" icon="Close" @click="cancelEdit(row)" />
          </div>
        </template>
      </el-table-column>

      <el-table-column prop="joinTime" label="加入时间" width="180" align="center">
        <template #default="{ row }">
          <span class="time-text">{{ formatTime(row.joinTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="160" align="center" fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            icon="Document"
            @click="handlePreviewReport(row)"
          >
            报告
          </el-button>
          <el-button
            type="danger"
            link
            icon="Remove"
            @click="handleKick(row)"
          >
            移出
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-box">
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 报告预览对话框 -->
    <ReportPreviewDialog ref="reportDialogRef" :class-id="classId" />
  </div>
</template>

<script setup>
// 班级成员管理：查看成员、改备注、移出班级，以及批量生成学习周报。
// 备注采用行内编辑（editingRemark + tempRemark 两个 UI 状态字段），
// 编辑态挂在行数据上而不是全局 state，才能做到同时只编辑一行。
import { ref, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Document } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getClassMembers, updateMemberRemark, removeMember } from '@/api/class'
import { generateBatchReports } from '@/api/report'
import ReportPreviewDialog from './ReportPreviewDialog.vue'
import dayjs from 'dayjs'

const props = defineProps({
  classId: { type: String, required: true }
})

const router = useRouter()
const loading = ref(false)
const memberList = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 10
const keyword = ref('')
const remarkInputRef = ref(null)
const tableRef = ref(null)
const reportDialogRef = ref(null)
const selectedMembers = ref([])
const batchGenerating = ref(false)

// 获取数据
const fetchMembers = async () => {
  loading.value = true
  try {
    const res = await getClassMembers({
      classId: props.classId,
      page: currentPage.value,
      size: pageSize,
      keyword: keyword.value
    })
    if (res.code === 200) {
      memberList.value = res.data.records.map(item => ({
        ...item,
        editingRemark: false, // UI 状态：是否正在编辑
        tempRemark: '' // 临时存储编辑中的备注
      }))
      total.value = res.data.total
    }
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchMembers()
}

// 格式化时间
const formatTime = (time) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

// 跳转个人主页
const goToProfile = (userId) => {
  router.push({ name: 'UserProfile', params: { id: userId } })
}

// === 备注编辑逻辑 ===
const startEdit = (row) => {
  // 同一时刻只允许一行进入编辑态：否则多个 input 同时展开，
  // 教师很容易改错行且无从察觉。
  memberList.value.forEach(item => { item.editingRemark = false })
  
  row.tempRemark = row.remark || ''
  row.editingRemark = true
  // 自动聚焦
  nextTick(() => {
    // 简单处理，实际上可能有多个 input，这里不做复杂 DOM 查找了，用户手动点一下也行
  })
}

const cancelEdit = (row) => {
  row.editingRemark = false
}

const saveRemark = async (row) => {
  // 内容没变就不发请求：备注是高频编辑字段，多余的写入会刷满审计日志。
  const oldRemark = row.remark
  const newRemark = row.tempRemark.trim()

  if (newRemark === oldRemark) {
    row.editingRemark = false
    return
  }

  try {
    const res = await updateMemberRemark({
      classId: props.classId,
      userId: row.userId,
      remark: newRemark
    })
    
    if (res.code === 200) {
      row.remark = newRemark
      row.editingRemark = false
      ElMessage.success('备注已更新')
    }
  } catch (error) {
    console.error(error)
  }
}

// === 移出学生逻辑 ===
// 移出会同时解除学生与班级的关系并让其失去该班作业访问权限，
// 且没有回收站，故必须确认，且取消时静默（.catch(() => {})）。
const handleKick = (row) => {
  ElMessageBox.confirm(
    `确定要将学生 "${row.username}" 移出班级吗？`,
    '移出确认',
    {
      confirmButtonText: '确定移出',
      cancelButtonText: '取消',
      type: 'warning',
      confirmButtonClass: 'el-button--danger'
    }
  ).then(async () => {
    try {
      const res = await removeMember({
        classId: props.classId,
        userId: row.userId
      })
      if (res.code === 200) {
        ElMessage.success('操作成功')
        fetchMembers() // 刷新列表
      }
    } catch (error) {
      console.error(error)
    }
  }).catch(() => {})
}

// === 报告相关逻辑 ===

// 多选变化
const handleSelectionChange = (selection) => {
  selectedMembers.value = selection
}

// 批量生成周报
const handleBatchGenerate = async () => {
  const students = selectedMembers.value
  if (students.length === 0) {
    ElMessage.warning('请先选择学生')
    return
  }
  batchGenerating.value = true
  try {
    const studentIds = students.map(s => s.userId || s.studentId)
    const res = await generateBatchReports({
      classId: props.classId,
      studentIds
    })
    if (res.code === 200) {
      const results = res.data || []
      const successCount = results.filter(r => r.success).length
      const failCount = results.filter(r => !r.success).length
      if (successCount > 0) {
        // 构造姓名映射
        const nameMap = {}
        students.forEach(s => {
          nameMap[s.userId || s.studentId] = s.remark || s.username || `学生 ${s.userId || s.studentId}`
        })
        reportDialogRef.value?.openWithResults(props.classId, results, nameMap)
        ElMessage.success(
          `报告生成完成：成功 ${successCount} 份` +
          (failCount > 0 ? `，失败 ${failCount} 份` : '')
        )
      } else {
        ElMessage.error(`全部 ${failCount} 份报告生成失败`)
      }
    }
  } catch (e) {
    ElMessage.error('批量生成失败：' + (e?.message || '网络异常'))
  } finally {
    batchGenerating.value = false
  }
}

// 单个学生预览报告
const handlePreviewReport = (row) => {
  const studentId = row.userId || row.studentId
  const name = row.remark || row.username || `学生 ${studentId}`
  reportDialogRef.value?.open([{ studentId, name }])
}

onMounted(() => {
  fetchMembers()
})
</script>

<style scoped>
.member-manager { padding: 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.right-info { display: flex; align-items: center; gap: 15px; }
.info-text { font-size: 13px; color: var(--text-secondary); }

.user-info-cell { display: flex; align-items: center; gap: 12px; cursor: pointer; padding: 4px 0; }
.user-info-cell:hover .username { color: var(--accent-primary); }
.user-text { display: flex; flex-direction: column; line-height: 1.3; }
.username { font-weight: 500; font-size: 14px; color: var(--text-primary); transition: color 0.2s; }
.email { font-size: 12px; color: var(--text-secondary); }

.remark-display { display: flex; align-items: center; gap: 8px; }
.remark-text { font-size: 14px; color: var(--text-regular); }
.no-remark { font-size: 13px; color: var(--text-placeholder); font-style: italic; }
.edit-icon { opacity: 0; transition: opacity 0.2s; }
.remark-display:hover .edit-icon { opacity: 1; }

.remark-edit { display: flex; align-items: center; gap: 5px; }
.time-text { font-family: 'Menlo', monospace; color: var(--text-regular); font-size: 13px; }

.pagination-box { display: flex; justify-content: center; margin-top: 20px; }
</style>