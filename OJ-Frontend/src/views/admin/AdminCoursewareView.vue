<template>
  <el-config-provider>
    <div class="page-container">
      <div class="page-header">
        <div>
          <div class="page-title">📂 课件资源库</div>
          <div class="page-desc">管理和共享您的教学 Slidev 课件</div>
        </div>
      </div>
      <div class="filter-wrapper">
        <div class="bar-left">
          <el-radio-group v-model="filterType" @change="handleFilterChange">
            <el-radio-button label="all">全部资源</el-radio-button>
            <el-radio-button label="mine">只看我的</el-radio-button>
          </el-radio-group>
        </div>
        
        <div class="bar-right">
          <div class="search-box">
            <el-input 
              v-model="keyword" 
              placeholder="搜索课件名称..." 
              prefix-icon="Search"
              clearable
              @keyup.enter="fetchData"
              @clear="fetchData"
            />
          </div>
          <el-button type="primary" icon="Upload" @click="handleOpenDialog('create')">
            上传/录入课件
          </el-button>
        </div>
      </div>

      <div class="table-wrapper">
        <el-table 
          :data="list" 
          style="width: 100%" 
          height="100%" 
          v-loading="loading"
          :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)' }"
        >
          <el-table-column prop="id" label="ID" width="80" align="center" />

          <el-table-column label="课件信息" min-width="250">
            <template #default="{ row }">
              <div class="info-cell">
                <div class="icon-wrapper">
                  <el-icon><DataBoard /></el-icon>
                </div>
                <div class="text-wrapper">
                  <div class="title" :title="row.title">{{ row.title }}</div>
                  <div class="desc" :title="row.description" v-if="row.description">
                    {{ row.description }}
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="类型" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.type === 1 ? 'success' : 'info'" effect="plain" size="small">
                {{ row.type === 1 ? '腾讯文档' : 'Slidev' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.privacy === 1" type="success" effect="plain" size="small">公开</el-tag>
              <el-tag v-else type="info" effect="plain" size="small">私有</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="classUseCount" label="使用班级" width="90" align="center">
            <template #default="{ row }">
              <span :class="{ 'count-zero': !row.classUseCount }">{{ row.classUseCount ?? 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column label="关联题单" width="150" align="center">
            <template #default="{ row }">
              <el-button
                v-if="row.problemListId"
                class="inline-link-btn"
                link
                type="primary"
                @click="handleGoProblemList(row.problemListId)"
              >
                #{{ row.problemListId }}
              </el-button>
              <span v-else class="text-gray">-</span>
            </template>
          </el-table-column>

          <el-table-column label="创建人" width="160">
            <template #default="{ row }">
              <div
                class="user-cell clickable-user"
                :title="getTeacherDisplayName(row)"
                @click="handleGoUserProfile(row.teacherId)"
              >
                <el-avatar :size="24" :src="row.teacherAvatar || row.avatar">
                  {{ getTeacherInitial(row) }}
                </el-avatar>
                <span class="username">{{ getTeacherDisplayName(row) }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="createTime" label="创建时间" width="150" align="center">
            <template #default="{ row }">
              <span class="time-text">{{ formatTime(row.createTime) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="300" align="center" fixed="right">
            <template #default="{ row }">
              <div>
                <el-button v-if="isOwner(row)" link type="primary" icon="Edit" @click="handleOpenDialog('edit', row)">编辑</el-button>
                <el-button v-if="isOwner(row)" link type="danger" icon="Delete" @click="handleDelete(row)">删除</el-button>
                <el-button link type="primary" icon="View" @click="handlePreview(row)">预览</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="pagination-wrapper">
        <el-pagination 
          background 
          layout="total, prev, pager, next, sizes" 
          :total="total" 
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>

      <CoursewareEditDialog ref="editDialogRef" @success="fetchData" />
    </div>
  </el-config-provider>
</template>

<script setup>
// 课件资源管理：教师与管理员共用。教师只能编辑/删除自己上传的课件（isOwner 判断），
// 管理员不受此限制。课件有两种形态：Slidev 编译产物（url）与腾讯文档嵌入链接。
// 判定依据是 teacherId 与当前登录用户是否一致，而非角色。
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { DataBoard } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, ElConfigProvider } from 'element-plus'
import dayjs from 'dayjs'
import CoursewareEditDialog from './components/CoursewareEditDialog.vue'
import { getCoursewareList, deleteCourseware } from '@/api/courseware'
import { useUserStore } from '@/stores/userStore'

const router = useRouter()

const loading = ref(false)
const list = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const filterType = ref('all')

const editDialogRef = ref(null)
const userStore = useUserStore()

const currentUserId = computed(() => userStore.userId)

const isOwner = (row) => String(row.teacherId) === String(currentUserId.value)
const formatTime = (t) => t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'

const getTeacherDisplayName = (row) => {
  return row.teacherName || row.nickname || row.username || '未知'
}

const getTeacherInitial = (row) => {
  const name = getTeacherDisplayName(row)
  return (name || 'U').charAt(0)
}

const fetchData = async () => {
  loading.value = true
  try {
    // onlyMine 由 filterType 推导：列表页只有「全部 / 我的」两种语义，
// 不额外维护一个独立的布尔状态，避免二者不同步。
    const params = {
      page: currentPage.value,
      size: pageSize.value,
      keyword: keyword.value,
      onlyMine: filterType.value === 'mine'
    }
    const res = await getCoursewareList(params)
    if (res.code === 200) {
      list.value = res.data.records
      total.value = res.data.total
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const handleFilterChange = () => {
  currentPage.value = 1
  fetchData()
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchData()
}

// 改变每页条数必须回到第 1 页：否则新页数下的第 2/3 页可能根本不存在，列表会空掉。
const handleSizeChange = (val) => {
  pageSize.value = val
  currentPage.value = 1
  fetchData()
}

const handleOpenDialog = (type, row) => {
  if (type === 'create') {
    const { href } = router.resolve({ name: 'AdminCoursewareCreate' })
    window.open(href, '_blank')
    return
  }
  if (editDialogRef.value) {
    editDialogRef.value.open('edit', row)
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确定要删除该课件吗？', '警告', {
    type: 'warning'
  }).then(async () => {
    const res = await deleteCourseware(row.id)
    if (res.code === 200) {
      ElMessage.success('已删除')
      fetchData()
    }
  })
}

const handlePreview = (row) => {
  if (row.url) window.open(row.url, '_blank')
}

const handleGoProblemList = (problemListId) => {
  if (!problemListId) return
  const { href } = router.resolve({
    name: 'ProblemListDetail',
    params: { id: problemListId }
  })
  window.open(href, '_blank')
}

const handleGoUserProfile = (teacherId) => {
  if (!teacherId) return
  const { href } = router.resolve({
    name: 'UserProfile',
    params: { id: teacherId }
  })
  window.open(href, '_blank')
}

onMounted(() => fetchData())
</script>

<style scoped>
.page-container {
  height: auto;
  display: flex;
  flex-direction: column;
  padding: 16px;
  margin: 0 auto;
  box-sizing: border-box;
  overflow: hidden;
  max-width: 1600px;
  width: 100%;
}

.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header .page-title { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.page-header .page-desc { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }

.filter-wrapper {
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--bg-surface);
  padding: 15px;
  border-radius: 8px 8px 0 0;
  border: 1px solid var(--border-light);
  border-bottom: none;
}
.bar-right { display: flex; align-items: center; gap: 12px; }
.search-box { width: 280px; }

.table-wrapper {
  flex: 1;
  min-height: 0;
  border: 1px solid var(--border-light);
  border-top: none;
  border-bottom: none;
  background: var(--bg-surface);
}

.pagination-wrapper {
  flex-shrink: 0;
  padding: 12px 20px;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 0 0 8px 8px;
  display: flex;
  justify-content: center;
}

.info-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.icon-wrapper {
  flex-shrink: 0;
  width: 40px; height: 40px; 
  background: var(--el-color-primary-light-9); border-radius: 8px; 
  display: flex; align-items: center; justify-content: center; 
  color: var(--accent-primary); font-size: 20px; 
}

.text-wrapper {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.title {
  font-weight: 500;
  color: var(--text-primary);
  font-size: 15px;
  margin-bottom: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.desc {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.brief-text {
  display: inline-block;
  width: 100%;
  color: var(--text-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.inline-link-btn {
  padding: 0;
}

.link-text { color: var(--accent-primary); font-family: monospace; }
.text-gray { color: var(--text-placeholder); }
.user-cell { display: flex; align-items: center; gap: 8px; }
.clickable-user { cursor: pointer; }
.username { font-size: 13px; color: var(--text-regular); }
.time-text { font-size: 13px; color: var(--text-secondary); font-family: monospace; }
</style>
