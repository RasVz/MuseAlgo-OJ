<template>
  <div class="class-view-container">
    <el-card shadow="never" class="main-card">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="title">我的班级库</span>
            <el-tag type="info" effect="plain" round class="count-tag">共 {{ total }} 个</el-tag>
          </div>
          
          <el-button type="primary" icon="Plus" round @click="joinDialogVisible = true">加入新班级</el-button>

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
          
          <div v-for="item in classList" :key="item.id" class="class-card" @click="enterClass(item)">
            <div class="card-cover">
              <div class="cover-icon">
                <el-icon><School /></el-icon>
              </div>
              <div class="class-initial">{{ item.name.charAt(0) }}</div>
            </div>

            <div class="card-body">
              <div class="class-name" :title="item.name">{{ item.name }}</div>
              <div class="card-footer">
                <div class="progress-label">学习进度</div>
                <el-progress :percentage="item.progress || 0" :stroke-width="6" :show-text="false" color="var(--el-color-primary)"/>
              </div>
            </div>

          </div>
        </div>
      </div>

      <div class="pagination-box">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="pageSize"
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
import { School } from '@element-plus/icons-vue' // 引入图标
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
// 当前页码（独立 ref，供翻页回调与 join 后刷新共用；queryParams 内引用同一 ref 保持同步）
const currentPage = ref(1)
const queryParams = ref({
  currentPage,
  pageSize: 10,
  userId: userStore.userId 
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
      fetchMyClasses(currentPage.value)
    }
  } catch {
    // 错误拦截器处理
  } finally {
    joining.value = false
  }
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchMyClasses(val)
}

const enterClass = (classItem) => {
  classStore.setClass(classItem)
  router.push({ name: 'ClassDirectory', params: { id:classItem.id } })
}
</script>

<style scoped>
/* 容器样式 */
.class-view-container {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.main-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 16px;
  border: none;
}

.main-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0; /* 清除默认 padding，由内部元素控制 */
  min-height: 0;
}

.card-header {
  display: flex; 
  justify-content: space-between; 
  align-items: center; 
  padding: 0 10px;
}
.header-left { display: flex; align-items: center; gap: 10px; }
.title { font-size: 18px; font-weight: bold; color: var(--text-primary); }
.count-tag { font-weight: normal; }

/* 滚动区域 */
.scroll-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px; /* 给 Grid 留出边距 */
}

/* --- GRID 布局核心 --- */
.class-grid {
  display: grid;
  /* 核心：一行 5 个，每个最小宽度 0 (防止溢出)，均分空间 */
  grid-template-columns: repeat(4, 1fr); 
  gap: 20px;
}

/* --- 卡片样式 --- */
.class-card {
  background: var(--bg-surface);
  border-radius: 12px;
  border: 1px solid var(--border-light);
  overflow: hidden;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  position: relative;
}

.class-card:hover {
  transform: translateY(-5px);
  box-shadow: var(--shadow-lg);
  border-color: var(--border-hover);
}

/* 卡片顶部封面 */
.card-cover {
  height: 90px;
  background: linear-gradient(135deg, #a1c4fd 0%, #c2e9fb 100%); /* 默认淡蓝渐变 */
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
  color: var(--text-inverse);
}

/* 为了让卡片更丰富，可以用 nth-child 给不同卡片不同的渐变色 (可选) */
.class-card:nth-child(5n+1) .card-cover { background: linear-gradient(135deg, #89f7fe 0%, #66a6ff 100%); }
.class-card:nth-child(5n+2) .card-cover { background: linear-gradient(135deg, #fbc2eb 0%, #a6c1ee 100%); }
.class-card:nth-child(5n+3) .card-cover { background: linear-gradient(135deg, #84fab0 0%, #8fd3f4 100%); }
.class-card:nth-child(5n+4) .card-cover { background: linear-gradient(135deg, #a18cd1 0%, #fbc2eb 100%); }

.cover-icon {
  font-size: 40px;
  opacity: 0.3;
  position: absolute;
  right: 10px;
  bottom: -10px;
  transform: rotate(-15deg);
}

.class-initial {
  font-size: 32px;
  font-weight: bold;
  text-shadow: 0 2px 4px rgba(0,0,0,0.1);
  z-index: 1;
}

/* 卡片内容主体 */
.card-body {
  padding: 15px;
  display: flex;
  flex-direction: column;
  flex: 1;
}

.class-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 12px;
  line-height: 1.4;
  height: 44px; /* 限制两行高度 */
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.class-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.todo-text {
  font-size: 12px;
  color: var(--accent-danger);
  background: var(--accent-danger-light, #fef0f0);
  padding: 2px 6px;
  border-radius: 4px;
}

/* 底部进度条区域 */
.card-footer {
  margin-top: auto; /* 推到底部 */
}

.progress-label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

/* 分页 */
.pagination-box {
  flex-shrink: 0;
  padding: 15px 0 20px;
  border-top: 1px solid var(--border-light);
  display: flex;
  justify-content: center;
  background: var(--bg-surface);
  border-bottom-left-radius: 16px;
  border-bottom-right-radius: 16px;
}

</style>
