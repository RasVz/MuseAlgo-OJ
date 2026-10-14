<template>
  <div class="directory-container">
    <el-card shadow="never" class="directory-card" v-loading="loading">
      <div class="header">
        <h3>课程大纲</h3>
        <span class="subtitle">共 {{ total }} 节课程</span>
      </div>

      <el-empty v-if="!loading && total === 0" description="老师暂未发布课程内容" />

      <div class="scroll-content" v-else>
        <div 
          v-for="chapter in currentPageData" 
          :key="chapter.id"
          class="chapter-item"
        >
          <div class="chapter-content">
            <span class="chapter-index">第 {{ chapter.index }} 章</span>
            <div class="divider"></div>
            <div class="text-info">
              <span class="chapter-title">{{ chapter.title }}</span>
              <span class="chapter-desc" v-if="chapter.description">{{ chapter.description }}</span>
            </div>
          </div>
          
          <el-button 
            type="primary" 
            round
            size="small"
            class="start-btn"
            @click="startLearning(chapter)"
          >
            开始学习
          </el-button>
        </div>
      </div>

      <div class="pagination-wrapper" v-if="total > 0">
        <el-pagination 
          background 
          layout="prev, pager, next" 
          :total="total" 
          :page-size="pageSize"
          v-model:current-page="currentPage"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
// 班级课件目录（学生视角）：按分页浏览本班的课件，点击进入学习页。
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getClassDirectory } from '@/api/class'

const router = useRouter()
const route = useRoute()

const loading = ref(false)
const allChapters = ref([])
const currentPage = ref(1)
const pageSize = 10

const total = computed(() => allChapters.value.length)
const classId = route.params.id

// 目录接口不分页（一次返回整班课件），前端切片模拟翻页。
// index 用全局下标而不是页内下标，否则第二页的序号会从 1 重新开始。
const currentPageData = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  const end = start + pageSize
  return allChapters.value.slice(start, end).map((item, idx) => ({
    ...item,
    index: start + idx + 1
  }))
})

const fetchData = async () => {
  if (!classId) return
  loading.value = true
  try {
    const res = await getClassDirectory(classId)
    if (res.code === 200) {
      allChapters.value = res.data
    }
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

// 在新标签页打开学习页：课件学习是长内容且带独立导航，
// 同标签打开会丢失这份目录页的位置。
const startLearning = (node) => {
  if (node.id != null) {
    const routeData = router.resolve({
      name: 'CourseStudy',
      params: {
        classId: classId,
        coursewareId: node.id
      }
    })

    window.open(routeData.href, '_blank')
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
/* 1. 容器占满高度 */
.directory-container {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding-bottom: 0px;
  box-sizing: border-box;
}

/* 2. 卡片变成 Flex 容器，撑满剩余空间 */
.directory-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  border: none;
  min-height: 0; /* 关键：防止溢出 */
}

/* 3. 穿透修改 Card Body */
.directory-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0; /* 把 padding 交给内部元素控制，方便滚动 */
  overflow: hidden; /* 禁止 Body 整体滚动 */
}

.header { 
  display: flex; 
  justify-content: space-between; 
  align-items: center; 
  padding: 20px 20px 15px; /* 补回 padding */
  border-bottom: 1px solid var(--border-light); 
  flex-shrink: 0; /* 防止被压缩 */
}
.header h3 { margin: 0; color: var(--text-primary); }
.subtitle { color: var(--text-secondary); font-size: 14px; }

/* 4. 滚动区域 */
.scroll-content {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}

.chapter-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 20px;
  border-bottom: 1px solid var(--bg-app);
  transition: background-color 0.2s;
}

.chapter-item:hover {
  background-color: #fafafa;
}

.chapter-content { display: flex; align-items: center; gap: 15px; font-size: 16px; flex: 1; }
.chapter-index { font-weight: bold; color: var(--text-secondary); min-width: 60px; white-space: nowrap; margin-left: 12px;}
.divider { width: 1px; height: 16px; background-color: #e0e0e0; }

.text-info { display: flex; flex-direction: column; gap: 4px; }
.chapter-title { font-weight: 500; color: var(--text-primary); }
.chapter-desc { font-size: 12px; color: var(--text-secondary); }

/* 5. 分页栏固定底部 */
.pagination-wrapper { 
  height: 55px;
  display: flex; 
  justify-content: center; 
  align-items: center;
  border-top: 1px solid var(--border-light);
  flex-shrink: 0; /* 禁止被压缩 */
}
</style>
