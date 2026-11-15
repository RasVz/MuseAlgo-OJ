<template>
  <div class="class-view-container">
    <el-card shadow="never" class="main-card">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <h1 class="title">我的班级库</h1>
            <span class="count-tag">共 {{ total }} 个</span>
          </div>
          
          <el-button type="primary" icon="Plus" @click="joinDialogVisible = true">加入新班级</el-button>

          <el-dialog v-model="joinDialogVisible" title="加入班级" style="top:12%" width="400px" append-to-body>
            <div style="text-align: center; margin: 20px 0;">
              <p style="margin-bottom: 15px; color: var(--text-regular);">请输入老师提供的 6 位邀请码</p>
              <el-input 
                v-model="inviteCode" 
                placeholder="6位数字邀请码"
                maxlength="6" 
                style="width: 200px; font-size: 18px; text-align: center;"
                input-style="text-align:center; letter-spacing: 2px;"
              />
            </div>
            <template #footer>
              <el-button @click="joinDialogVisible = false">取消</el-button>
              <el-button type="primary" @click="handleJoinClass" :loading="joining">立即加入</el-button>
            </template>
          </el-dialog>
        </div>
      </template>

      <div class="scroll-container" v-loading="loading">
        
        <el-empty v-if="!loading && classList.length === 0" description="暂未加入任何班级" />

        <div v-else class="class-grid">
          
          <button v-for="item in classList" :key="item.id" type="button" class="class-card" :aria-label="`进入班级：${item.name}`" @click="enterClass(item)">
            <span class="book-emblem"><el-icon aria-hidden="true"><Reading /></el-icon>班级课程</span>
            <span class="class-name" :title="item.name">{{ item.name }}</span>
            <span v-if="item.school" class="class-school" :title="item.school">{{ item.school }}</span>
            <span class="class-entry">
              <span>进入班级</span>
              <el-icon aria-hidden="true"><TopRight /></el-icon>
            </span>
          </button>
        </div>
      </div>

      <div class="pagination-box">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="queryParams.size"
          :current-page="currentPage"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Reading, TopRight } from '@element-plus/icons-vue'
import { useClassContextStore } from '@/stores/classContext'
import { getMyClasses, joinClassByCode } from '@/api/class'
import { useUserStore } from '@/stores/userStore'

const router = useRouter()
const classStore = useClassContextStore()
const userStore = useUserStore()

// --- 状态定义 ---
const loading = ref(false)
const classList = ref([])

const total = ref(0)
// 页码与接口的 page/size 参数保持同步，每页展示 12 个班级。
const currentPage = ref(1)
const queryParams = ref({
  page: currentPage,
  size: 12
})

const joinDialogVisible = ref(false)
const inviteCode = ref('')
const joining = ref(false)

// --- 初始化 ---
onMounted(() => {
  fetchMyClasses()
})

// --- 业务方法 ---
const fetchMyClasses = async () => {
  if (!userStore.userId) return
  loading.value = true
  try {
    const res = await getMyClasses(queryParams.value)
    if (res.code === 200) {
      classList.value = res.data.records
      total.value = res.data.total
    }
  } catch (error) {
    console.error('获取班级失败', error)
  } finally {
    loading.value = false
  }
}

const handleJoinClass = async () => {
  if (inviteCode.value.length !== 6) {
    return ElMessage.warning('请输入6位邀请码')
  }
  
  joining.value = true
  try {
    const res = await joinClassByCode({
      code: inviteCode.value,
      userId: userStore.userId.toString()
    })
    if (res.message === '邀请码无效或已过期') {
      ElMessage.error('邀请码无效或已过期')
    } else if (res.message === '您已在班级中，无需重复加入') {
      ElMessage.error('您已在班级中，无需重复加入')
    } else if (res.code === 200) {
      ElMessage.success('加入班级成功！')
      joinDialogVisible.value = false
      fetchMyClasses()
    }
  } catch {
    // 错误拦截器处理
  } finally {
    joining.value = false
  }
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchMyClasses()
}

const enterClass = (classItem) => {
  classStore.setClass(classItem)
  router.push({ name: 'ClassDirectory', params: { id:classItem.id } })
}
</script>

<style scoped>
.class-view-container {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.main-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-card);
  border: 1px solid var(--border-light);
}
.main-card :deep(.el-card__header) { padding: 16px 20px; }
.main-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0;
  min-height: 0;
}
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.header-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.title { margin: 0; font-size: 18px; line-height: 1.4; font-weight: 700; color: var(--text-primary); }
.count-tag { color: var(--text-secondary); font-size: 13px; white-space: nowrap; }
.scroll-container { flex: 1; overflow-y: auto; padding: 16px; container-type: inline-size; }
.class-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  align-content: start;
}
.class-card {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 144px;
  padding: 14px 16px;
  text-align: left;
  font: inherit;
  color: var(--text-primary);
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-card);
  cursor: pointer;
  transition: border-color 0.2s ease;
}
.class-card:hover { border-color: var(--border-hover); }
.class-card:focus-visible { outline: 3px solid var(--accent-primary); outline-offset: 3px; }
.book-emblem { display: flex; align-items: center; gap: 5px; margin-bottom: 8px; color: var(--text-secondary); font-size: 11px; line-height: 1.4; letter-spacing: 0.06em; }
.book-emblem .el-icon { font-size: 14px; }
.class-name {
  font-family: var(--font-family);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0.02em;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  flex-shrink: 0;
}
.class-school { margin-top: 6px; color: var(--text-regular); font-size: 12px; line-height: 1.4; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.class-entry {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding-top: 8px;
  color: var(--accent-primary);
  font-size: 12px;
  line-height: 1.4;
}
.pagination-box {
  flex-shrink: 0;
  height: 56px;
  padding: 0 24px;
  border-top: 1px solid var(--border-light);
  display: flex;
  justify-content: center;
  align-items: center;
}
.pagination-box :deep(.el-pagination.is-background .btn-prev),
.pagination-box :deep(.el-pagination.is-background .btn-next),
.pagination-box :deep(.el-pagination.is-background .el-pager li) {
  border-radius: var(--radius-control);
}
@container (max-width: 759px) {
  .class-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@container (max-width: 559px) {
  .class-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@container (max-width: 339px) {
  .class-grid { grid-template-columns: minmax(0, 1fr); }
}
@media (min-width: 1051px) and (max-height: 760px) {
  .main-card :deep(.el-card__header) { padding: 14px 20px; }
  .scroll-container { padding: 16px 20px; }
  .class-grid { gap: 12px; }
  .class-card { height: 136px; padding-top: 10px; padding-bottom: 8px; }
  .book-emblem { margin-bottom: 6px; }
  .class-name { font-size: 15px; }
  .class-school { margin-top: 4px; }
  .class-entry { padding-top: 6px; }
  .pagination-box { padding: 0 24px; }
}
@media (max-width: 1050px) {
  .class-view-container, .main-card { height: auto; }
  .scroll-container { min-height: 280px; overflow: visible; }
}
@media (max-width: 600px) {
  .main-card :deep(.el-card__header) { padding: 20px 16px; }
  .card-header { flex-wrap: wrap; }
  .scroll-container { padding: 16px; }
}
@media (prefers-reduced-motion: reduce) {
  .class-card { transition: none; }
  .class-card:hover { transform: none; }
}
</style>
