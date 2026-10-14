<template>
  <div class="class-manager">
    <div class="page-header">
      <div>
        <div class="page-title">我的教学班级</div>
        <div class="page-subtitle">共管理 {{ classList.length }} 个班级</div>
      </div>
      <el-button type="primary" round @click="openCreateDialog">
        ＋ 创建新班级
      </el-button>
    </div>

    <el-card shadow="never" class="main-card">
      <div class="table-wrap" v-loading="listLoading">
        <el-table
          v-if="classList.length > 0"
          :data="classList"
          style="width: 100%"
          stripe
          :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)', fontWeight: 700 }"
        >
          <el-table-column prop="name" label="班级名称" min-width="190">
            <template #default="{ row }">
              <div class="name-wrapper">
                <el-avatar :size="30" class="mini-icon" :style="{ background: stringToColor(row.name) }">
                  {{ (row.name || '?').charAt(0) }}
                </el-avatar>
                <div class="name-meta">
                  <span class="class-name-text">{{ row.name || '-' }}</span>
                  <span class="class-id-text">ID: {{ row.id }}</span>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="description" label="简介" min-width="260">
            <template #default="{ row }">
              <span v-if="row.description" class="desc-text" :title="row.description">{{ row.description }}</span>
              <span v-else class="placeholder-text">暂无简介</span>
            </template>
          </el-table-column>

          <el-table-column prop="school" label="所属机构" min-width="150">
            <template #default="{ row }">
              <el-tag v-if="row.school" type="info" effect="plain" round>{{ row.school }}</el-tag>
              <span v-else class="placeholder-text">-</span>
            </template>
          </el-table-column>

          <el-table-column label="学生" width="110" align="center">
            <template #default="{ row }">
              <span class="student-badge">{{ row.studentCount || 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column label="创建时间" width="130" align="center">
            <template #default="{ row }">
              <span class="time-text">{{ formatTime(row.createTime) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="130" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                type="primary"
                size="small"
                round
                @click="$router.push({ name: 'AdminClassDetail', params: { classId: row.id } })"
              >
                进入管理
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-else class="empty-wrap">
          <el-empty description="暂无管理的班级，点击右上角创建吧！" />
        </div>
      </div>
    </el-card>

    <CreateClassDialog
      v-model="dialogVisible"
      :teacher-id="userStore.userId"
      @created="fetchList"
    />
  </div>
</template>

<script setup>
// 教师端的「我的班级」列表。数据源是 getTeacherClassList（传当前用户 ID），
// 因此教师看不到他人班级；管理员在这一页看到的是自己作为教师身份创建的班级，
// 跨教师的全局视图在 /admin/classes。
// 新建入口同时响应页面内按钮与全局 authBus 事件，供顶部导航栏复用。
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/userStore'
import { authBus } from '@/utils/eventBus'
import { getTeacherClassList } from '@/api/class'
import CreateClassDialog from './components/CreateClassDialog.vue'

const userStore = useUserStore()

// --- 状态定义 ---
const classList = ref([])
const listLoading = ref(false)
const dialogVisible = ref(false)

// --- 方法定义 ---
const fetchList = async () => {
  listLoading.value = true
  try {
    const res = await getTeacherClassList(userStore.userId)
    if (res.code === 200) {
      classList.value = res.data || []
    } else {
      ElMessage.error(res.msg || res.message || '获取班级列表失败')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('获取班级列表失败，请稍后重试')
  } finally {
    listLoading.value = false
  }
}

const openCreateDialog = () => {
  if (!userStore.isTeacher) return
  dialogVisible.value = true
}

const formatTime = (val) => {
  if (!val) return '-'
  return val.replace('T', ' ').substring(0, 10)
}

// 仅为卡片配色的装饰性哈希：同一个班名永远得到同一个颜色，便于用户凭颜色记忆班级。
// 与业务逻辑无关，不参与任何判断。
const stringToColor = (str) => {
  if (!str) return '#409EFF'
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  const c = (hash & 0x00ffffff).toString(16).toUpperCase()
  return '#' + '000000'.substring(0, 6 - c.length) + c
}

// 事件总线订阅必须在卸载时退订：否则组件销毁后事件仍会触发 openCreateDialog，
// 访问到已失效的响应式状态。
let unsubCreate = null

onMounted(() => {
  fetchList()
  unsubCreate = authBus.on('class:open-create-dialog', openCreateDialog)
})

onUnmounted(() => {
  unsubCreate?.()
})
</script>

<style scoped>
.class-manager {
  height: auto;
  display: flex;
  flex-direction: column;
  padding: 16px;
  box-sizing: border-box;
}

/* 页头布局 */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-title {
  font-size: 19px;
  font-weight: 700;
  color: var(--text-primary);
}
.page-subtitle {
  font-size: 12px;
  color: var(--text-secondary);
}

/* 主体卡片 */
.main-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  border: 1px solid var(--border-light);
  min-height: 0;
  overflow: hidden;
}

.main-card :deep(.el-card__body) {
  flex: 1;
  overflow: hidden;
  padding: 0;
}

.table-wrap {
  height: 100%;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: auto;
}

:deep(.el-table) {
  border-radius: 10px;
  overflow: hidden;
}

:deep(.el-table th.el-table__cell) {
  height: 48px;
}

:deep(.el-table td.el-table__cell) {
  height: 60px;
}

:deep(.el-table__row:hover > td.el-table__cell) {
  background: var(--accent-primary-light) !important;
}

/* 文本样式优化 */
.name-wrapper {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mini-icon {
  color: var(--text-on-accent);
  font-size: 12px;
  font-weight: 700;
  box-shadow: 0 3px 8px rgba(0, 0, 0, 0.12);
}
.name-meta {
  display: flex;
  flex-direction: column;
}
.class-name-text {
  font-weight: 600;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 1.2;
}
.class-id-text {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-secondary);
}

.desc-text {
  display: inline-block;
  max-width: 100%;
  color: var(--text-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.student-badge {
  display: inline-flex;
  min-width: 28px;
  height: 24px;
  padding: 0 8px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: var(--el-color-primary-light-9);
  color: var(--accent-primary);
  font-weight: 700;
  font-family: 'Arial', sans-serif;
}

.time-text {
  color: var(--text-secondary);
  font-family: 'Menlo', monospace;
  font-size: 12px;
}

.placeholder-text {
  color: var(--text-placeholder);
}

.empty-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
