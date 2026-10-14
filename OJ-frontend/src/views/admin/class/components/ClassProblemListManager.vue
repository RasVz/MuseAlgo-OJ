<template>
  <div class="class-lists-container">
    <div class="filter-section">
       <div class="filter-bar">
        <el-input 
          v-model="searchKeyword" 
          placeholder="搜索题单..." 
          prefix-icon="Search"
          class="search-input"
          clearable
          @keyup.enter="fetchData"
        />
        
        <div class="action-box">
          <el-button type="primary" icon="Plus" @click="handleCreate">
            新建题单
          </el-button>
        </div>
      </div>
    </div>

    <div class="table-container">
      <el-card shadow="never" class="main-card" :body-style="{ padding: '0', height: '100%', display: 'flex', flexDirection: 'column' }">
        <el-table 
          :data="listData" 
          style="width: 100%; flex: 1;" 
          height="100%" 
          v-loading="loading"
          :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)', fontWeight: 'bold' }"
          row-key="id"
          @row-click="goDetail"
        >
          <el-table-column label="题单名称" min-width="300">
            <template #default="{ row }">
              <div class="list-title-wrapper">
                <div class="list-icon-box">
                  <el-icon><List /></el-icon>
                </div>
                <div class="list-info">
                  <div class="list-name">
                    {{ row.title }}
                    <el-tag type="warning" size="small" effect="plain" class="class-tag">班级</el-tag>
                  </div>
                  <div class="list-desc text-truncate">{{ row.description || '暂无简介' }}</div>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="problemCount" label="题目数" width="120" align="center">
            <template #default="{ row }">
              <span class="mono-font">{{ row.problemCount || 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="updateTime" label="最后更新" width="180" align="right">
            <template #default="{ row }">
              <span class="text-secondary text-small">{{ formatTime(row.updateTime || row.createTime) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="200" align="center" fixed="right">
            <template #default="{ row }">
              <el-button 
                type="primary" 
                link 
                icon="Edit"
                @click.stop="openEditPage(row)"
              >
                编辑题单
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <div class="pagination-footer">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 新建/编辑题单弹窗 -->
    <el-dialog 
      :title="dialogTitle" 
      v-model="dialogVisible" 
      width="500px"
      @close="resetForm"
      destroy-on-close
    >
      <el-form 
        :model="formData" 
        :rules="rules" 
        ref="formRef" 
        label-width="80px"
      >
        <el-form-item label="题单名称" prop="title">
          <el-input 
            v-model="formData.title" 
            placeholder="请输入题单名称" 
            maxlength="50" 
            show-word-limit 
          />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input 
            type="textarea" 
            v-model="formData.description" 
            placeholder="请输入题单描述(可选)" 
            :rows="4"
            maxlength="200" 
            show-word-limit 
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSubmit" :loading="submitLoading">确定</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 班级题单管理：教师在班级范围内创建与修改题单（区别于官方题单与个人题单）。
// 题单内的题目增删排序跳转到全局题单编辑器完成，这里只管题单本身。
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { List } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { 
  getClassProblemLists, 
  addClassProblemList, 
  updateClassProblemList, 
} from '@/api/classProblemList'
import dayjs from 'dayjs'

const props = defineProps({
  classId: {
    type: [Number, String],
    required: true
  }
})

const router = useRouter()
const loading = ref(false)
const listData = ref([])
const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = 10
const total = ref(0)

// 弹窗表单相关
const dialogVisible = ref(false)
const dialogTitle = ref('')
const submitLoading = ref(false)
const formRef = ref(null)

const formData = reactive({
  id: null,
  title: '',
  description: ''
})

const rules = {
  title: [
    { required: true, message: '请输入题单名称', trigger: 'blur' },
    { min: 2, max: 50, message: '长度在 2 到 50 个字符', trigger: 'blur' }
  ]
}

const formatTime = (time) => time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'

const handleCreate = () => {
  // 跳转到创建页面，并通过 query 参数传递 classId
  router.push({
    path: '/problem/list/create/edit',
    query: { classId: props.classId }
  })
}

const openEditPage = (row) => {
  router.push({
    path: `/problem/list/${row.id}/edit`,
    query: { classId: props.classId }
  })
}

const fetchData = async () => {
  if (!props.classId) return
  loading.value = true
  try {
    const params = {
      page: currentPage.value,
      size: pageSize,
      keyword: searchKeyword.value
    }

    const res = await getClassProblemLists(props.classId, params)
    if (res.code === 200) {
      listData.value = res.data?.records || []
      total.value = res.data?.total || 0
    } else {
      ElMessage.error(res.message || '获取题单列表失败')
    }
  } catch (error) {
    console.error('获取题单列表失败:', error)
    ElMessage.error('获取题单列表失败')
  } finally {
    loading.value = false
  }
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchData()
}

// 提交表单 (新增或修改)
const handleSubmit = async () => {
  if (!formRef.value) return
  
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    
    submitLoading.value = true
    try {
      let res
      if (formData.id) {
        // 修改
        res = await updateClassProblemList(props.classId, formData.id, {
          title: formData.title,
          description: formData.description
        })
      } else {
        // 新增 - 班级题单需要传递 classId 和 type=3
        res = await addClassProblemList(props.classId, {
          title: formData.title,
          description: formData.description,
          type: 3,  // 班级题单
          classId: props.classId
        })
      }
      
      if (res.code === 200) {
        ElMessage.success(formData.id ? '修改成功' : '添加成功')
        dialogVisible.value = false
        fetchData()
      } else {
        ElMessage.error(res.message || (formData.id ? '修改失败' : '添加失败'))
      }
    } catch (error) {
      console.error(formData.id ? '修改失败:' : '添加失败:', error)
      ElMessage.error(formData.id ? '修改失败' : '添加失败')
    } finally {
      submitLoading.value = false
    }
  })
}

// 重置表单
const resetForm = () => {
  formData.id = null
  formData.title = ''
  formData.description = ''
  if (formRef.value) {
    formRef.value.clearValidate()
  }
}

// 跳转到题单详情
const goDetail = (row) => {
  if (row && row.id) {
    router.push(`/problem/list/${row.id}`)
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.class-lists-container { 
  height: 100%;       
  display: flex; 
  flex-direction: column;
  box-sizing: border-box;
  padding: 10px 20px 0 20px;
  overflow: hidden;   
  background-color: var(--bg-surface-hover);
}

.navbar-title-section {
  display: flex;
  align-items: center;
  gap: 10px;
}
.page-title { margin: 0; font-size: 18px; color: var(--text-primary); font-weight: 600; }
.sub-title { color: var(--text-secondary); font-size: 12px; margin-top: 2px; }

.filter-section {
  padding: 10px 0;
  flex-shrink: 0;
}
.filter-bar { 
  display: flex; 
  align-items: center; 
}

.action-box {
  margin-left: auto; 
}

.search-input { width: 250px; }
.ml-4 { margin-left: 16px; }

.table-container {
  flex: 1;            
  overflow: hidden;   
  margin-bottom: 10px;
  background-color: var(--text-on-accent);
  border-radius: 8px;
}
.main-card {
  height: 100%;
  border: none;
  border-radius: 8px;
}

.pagination-footer {
  flex-shrink: 0;
  height: 50px;
  display: flex; 
  justify-content: center;
  align-items: center;
  background-color: var(--text-on-accent);
  border-top: 1px solid var(--border-light);
  border-radius: 8px 8px 0 0;
}

.list-title-wrapper { display: flex; align-items: center; gap: 15px; padding: 5px 0; }
.list-icon-box {
  width: 42px; height: 42px; border-radius: 10px;
  background: linear-gradient(135deg, var(--accent-warning) 0%, var(--accent-danger) 100%);
  color: var(--text-on-accent); font-size: 20px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.list-info { flex: 1; overflow: hidden; }
.list-name { font-weight: bold; font-size: 15px; color: var(--text-primary); display: flex; align-items: center; gap: 8px; }
.class-tag { height: 18px; line-height: 16px; padding: 0 4px; }
.list-desc { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
.text-truncate { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mono-font { font-family: 'Menlo', monospace; font-weight: 600; }
.text-secondary { color: var(--text-secondary); }
.text-small { font-size: 13px; }
</style>
