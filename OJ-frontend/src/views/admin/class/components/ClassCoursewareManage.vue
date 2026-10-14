<template>
  <div class="courseware-manager">
    <div class="manager-header">
      <el-button type="primary" icon="Plus" @click="openSelectDialog">
        添加课件
      </el-button>
    </div>

    <el-table 
      :data="list" 
      style="width: 100%" 
      v-loading="loading"
      row-key="id"
      :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)' }"
    >
      <el-table-column label="排序" width="80" align="center">
        <template #default="{ $index }">
          <div class="sort-actions">
            <el-button 
              link size="small" icon="ArrowUp" 
              :disabled="$index === 0" @click="handleSort($index, -1)"
            />
            <el-button 
              link size="small" icon="ArrowDown" 
              :disabled="$index === list.length - 1" @click="handleSort($index, 1)"
            />
          </div>
        </template>
      </el-table-column>

      <el-table-column prop="title" label="课件名称" min-width="200">
        <template #default="{ row }">
          <div class="title-cell">
            <el-icon class="icon-ppt"><DataBoard /></el-icon>
            <span class="title-text">{{ row.title }}</span>
            <el-tag v-if="!row.isVisible" size="small" type="info" effect="plain">已隐藏</el-tag>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="关联练习" width="150" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.problemListId" type="success" size="small">ID: {{ row.problemListId }}</el-tag>
          <span v-else class="text-gray">-</span>
        </template>
      </el-table-column>

      <el-table-column label="学生可见" width="150" align="center">
        <template #default="{ row }">
          <el-switch 
            v-model="row.isVisible" 
            :active-value="true" 
            :inactive-value="false"
            @change="handleVisibilityChange(row)"
          />
        </template>
      </el-table-column>

      <el-table-column label="操作" width="150" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" icon="Delete" @click="handleRemove(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog 
      v-model="selectDialogVisible" 
      title="从课件资源库添加" 
      width="850px"
      top="8vh"
      append-to-body
      class="custom-dialog"
    >
      <div class="select-toolbar">
        <el-radio-group v-model="cwFilterType" @change="handleCwFilterChange">
          <el-radio-button label="all">全部资源</el-radio-button>
          <el-radio-button label="mine">只看自己</el-radio-button>
        </el-radio-group>
        
        <el-input 
          v-model="cwKeyword" 
          placeholder="搜索课件名称..." 
          prefix-icon="Search"
          clearable
          class="search-input"
          @keyup.enter="fetchCoursewareData"
          @clear="fetchCoursewareData"
        />
      </div>

      <el-table 
        :data="coursewareList" 
        height="400px" 
        v-loading="cwLoading"
        :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)' }"
      >
        <el-table-column label="课件标题" min-width="220">
          <template #default="{ row }">
            <div class="title-cell">
              <el-icon class="icon-ppt"><DataBoard /></el-icon>
              <div class="text-wrapper">
                <span class="title-text" :title="row.title">{{ row.title }}</span>
                <span class="desc-text" v-if="row.description" :title="row.description">{{ row.description }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="关联题单" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.problemListId" type="info" size="small" effect="plain">#{{ row.problemListId }}</el-tag>
            <span v-else class="text-gray">-</span>
          </template>
        </el-table-column>

        <el-table-column label="创建人" width="150">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="24" :src="row.teacherAvatar || row.avatar">
                {{ getTeacherInitial(row) }}
              </el-avatar>
              <span class="username">{{ getTeacherDisplayName(row) }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button 
              type="primary" 
              link 
              icon="Plus" 
              :disabled="isAlreadyAdded(row.url)"
              @click="handleAddSelected(row)"
            >
              {{ isAlreadyAdded(row.url) ? '已添加' : '添加' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination 
          background 
          layout="total, prev, pager, next" 
          :total="cwTotal" 
          v-model:current-page="cwCurrentPage"
          v-model:page-size="cwPageSize"
          @current-change="handleCwPageChange"
        />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
// 班级课件目录管理：从课件资源库挑选课件挂到班级，也可调整班级内的可见性与顺序。
// 课件本体在资源库里维护（CoursewareEditorView），本组件只维护「班级 ↔ 课件」的关联与顺序。
import { ref, onMounted } from 'vue'
import { DataBoard } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

// 班级课件 API
import { 
  getClassDirectory, 
  addCoursewareToClass, 
  updateClassCourseware, 
  removeCoursewareFromClass,
  updateSortBatch
} from '@/api/classCourseware'

// 引入资源库课件 API
import { getCoursewareList } from '@/api/courseware'

const props = defineProps({
  classId: { type: String, required: true }
})

// === 班级课件列表状态 ===
const loading = ref(false)
const list = ref([])

// === 资源库课件选择状态 ===
const selectDialogVisible = ref(false)
const cwLoading = ref(false)
const coursewareList = ref([])
const cwTotal = ref(0)
const cwCurrentPage = ref(1)
const cwPageSize = ref(10)
const cwKeyword = ref('')
const cwFilterType = ref('all')

// === 获取班级课件数据 ===
const fetchData = async () => {
  loading.value = true
  try {
    const res = await getClassDirectory(props.classId)
    if (res.code === 200) {
      list.value = res.data.map(item => ({
        ...item,
        isVisible: Boolean(item.isVisible === undefined ? true : item.isVisible)
      }))
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('获取课件列表失败')
  } finally {
    loading.value = false
  }
}

// === 资源库相关方法 ===
const fetchCoursewareData = async () => {
  cwLoading.value = true
  try {
    const params = {
      page: cwCurrentPage.value,
      size: cwPageSize.value,
      keyword: cwKeyword.value,
      onlyMine: cwFilterType.value === 'mine'
    }
    const res = await getCoursewareList(params)
    if (res.code === 200) {
      coursewareList.value = res.data.records
      cwTotal.value = res.data.total
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('获取资源库列表失败')
  } finally {
    cwLoading.value = false
  }
}

const openSelectDialog = () => {
  selectDialogVisible.value = true
  cwCurrentPage.value = 1
  fetchCoursewareData()
}

const handleCwFilterChange = () => {
  cwCurrentPage.value = 1
  fetchCoursewareData()
}

const handleCwPageChange = (val) => {
  cwCurrentPage.value = val
  fetchCoursewareData()
}

// 查重：防止重复添加同一课件 (基于url判断)
const isAlreadyAdded = (url) => {
  return list.value.some(item => item.url === url)
}

// 执行添加课件到班级
const handleAddSelected = async (row) => {
  try {
    const res = await addCoursewareToClass({
      classId: props.classId,
      title: row.title,
      url: row.url,
      problemListId: row.problemListId || null,
      description: row.description
    })
    if (res.code === 200) {
      ElMessage.success(`《${row.title}》已成功添加到当前班级`)
      fetchData() // 刷新班级课件列表
    } else {
      ElMessage.warning(res.message || '添加失败')
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('添加失败')
  }
}

// 辅助方法：获取教师信息
const getTeacherDisplayName = (row) => {
  return row.teacherName || row.nickname || row.username || '未知'
}
const getTeacherInitial = (row) => {
  const name = getTeacherDisplayName(row)
  return (name || 'U').charAt(0)
}

// === 排序功能 ===
const handleSort = async (index, step) => {
  const targetIndex = index + step
  if (targetIndex < 0 || targetIndex >= list.value.length) return
  
  // 本地交换位置
  const temp = list.value[index]
  list.value[index] = list.value[targetIndex]
  list.value[targetIndex] = temp
  
  // 构建批量更新的排序数据
  const sortList = list.value.map((item, idx) => ({
    id: item.relationId,
    sort: idx + 1
  }))
  
  try {
    const res = await updateSortBatch(sortList)
    if (res.code === 200) {
      ElMessage.success('排序已更新')
    } else {
      // 如果后端更新失败，恢复原来的顺序
      fetchData()
      ElMessage.error('排序更新失败')
    }
  } catch (e) {
    console.error(e)
    // 恢复原来的顺序
    fetchData()
    ElMessage.error('排序更新失败')
  }
}

// === 学生可见功能 ===
const handleVisibilityChange = async (row) => {
  try {
    const res = await updateClassCourseware({
      id: row.relationId,
      isVisible: row.isVisible
    })
    if (res.code === 200) {
      ElMessage.success(row.isVisible ? '课件已对学生可见' : '课件已对学生隐藏')
    } else {
      // 如果后端更新失败，恢复原来的状态
      row.isVisible = !row.isVisible
      ElMessage.error('更新失败')
    }
  } catch (e) {
    console.error(e)
    // 恢复原来的状态
    row.isVisible = !row.isVisible
    ElMessage.error('更新失败')
  }
}

// === 移除功能 ===
const handleRemove = (row) => {
  ElMessageBox.confirm('确定要从班级移除该课件吗？(课件资源本身不会被删除)', '提示', {
    type: 'warning',
    confirmButtonText: '确定移除',
    cancelButtonText: '取消'
  }).then(async () => {
    try {
      const res = await removeCoursewareFromClass(row.relationId)
      if (res.code === 200) {
        ElMessage.success('移除成功')
        fetchData()
      } else {
        ElMessage.error(res.message || '移除失败')
      }
    } catch (e) {
      console.error(e)
      ElMessage.error('移除失败')
    }
  }).catch(() => {
    // 用户取消操作
  })
}

onMounted(() => fetchData())
</script>

<style scoped>
.courseware-manager { padding: 0; }
.manager-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.sort-actions { display: flex; flex-direction: column; align-items: center; gap: 2px; }

/* 单元格通用样式 */
.title-cell { display: flex; align-items: center; gap: 8px; }
.text-wrapper { display: flex; flex-direction: column; min-width: 0; }
.title-text { font-weight: 500; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.desc-text { font-size: 12px; color: var(--text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
.icon-ppt { color: var(--accent-warning); font-size: 18px; flex-shrink: 0; }
.url-text { font-family: monospace; color: var(--text-secondary); font-size: 12px; }
.text-gray { color: var(--text-placeholder); }

/* 用户信息样式 */
.user-cell { display: flex; align-items: center; gap: 8px; }
.username { font-size: 13px; color: var(--text-regular); }

/* 表单信息 */
.form-tip { font-size: 12px; color: var(--text-secondary); margin-top: 4px; line-height: 1.4; }

/* 资源选择弹窗内部布局 */
.select-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px; }
.search-input { width: 280px; }
.pagination-wrapper { margin-top: 15px; display: flex; justify-content: flex-end; }
</style>