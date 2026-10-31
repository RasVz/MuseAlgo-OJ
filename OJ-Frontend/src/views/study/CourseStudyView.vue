<template>
  <div class="study-container">
    <header class="study-header">
      <div class="header-left">
        <span class="course-title">{{ currentCourseware.title || '正在学习' }}</span>
      </div>
      <div class="header-right">
        <el-button
          :type="showPractice ? 'primary' : 'default'"
          :icon="Edit"
          circle
          @click="togglePractice"
          title="显示/隐藏练习面板"
        />
      </div>
    </header>

    <div class="study-body">
      <CourseDirectoryTree
        v-model:collapsed="isSidebarCollapsed"
        :tree="directoryTree"
        :current-id="currentId"
        ref="treeRef"
        @node-click="handleNodeClick"
      />

      <main class="main-content">
        <div v-if="currentCourseware.url" class="slidev-wrapper">
          <TencentDocsPlayer
            v-if="isTencentDocsUrl(currentCourseware.url)"
            :url="currentCourseware.url"
          />
          <iframe
            v-else
            :key="iframeKey"
            :src="currentCourseware.url"
            frameborder="0"
            class="slidev-iframe"
            allow="fullscreen"
            @load="handleIframeLoad" 
          ></iframe>
        </div>
        <div v-else class="empty-state">
          <el-empty description="请在左侧选择一节课程开始学习" />
        </div>
      </main>

      <PracticePanel
        :open="showPractice"
        :problem-list="problemList"
        @close="showPractice = false"
        @open-problem="openProblem"
      />
    </div>

    <el-dialog
      v-model="problemDialogVisible"
      title="题目详情"
      width="80%"
      top="5vh"
      class="problem-dialog"
      append-to-body
    >
      <ProblemDetailView v-if="currentProblemId" :id="currentProblemId" :embedded="true" />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Edit } from '@element-plus/icons-vue'
import { getClassDirectory } from '@/api/class'
import { getCoursewareDetail } from '@/api/courseware'
import { getListProblems } from '@/api/problemList'
import ProblemDetailView from '@/views/problem/ProblemDetailView.vue'
import CourseDirectoryTree from './components/CourseDirectoryTree.vue'
import PracticePanel from './components/PracticePanel.vue'
import TencentDocsPlayer from '@/components/TencentDocsPlayer.vue'

const route = useRoute()
const router = useRouter()

// 状态
const isSidebarCollapsed = ref(false)
const showPractice = ref(true)
const currentId = ref(null)
const currentCourseware = ref({ title: '', url: '' })
const directoryTree = ref([])
const problemList = ref([])
const iframeKey = ref('')

// 题目弹窗相关
const problemDialogVisible = ref(false)
const currentProblemId = ref(null)
const treeRef = ref(null)

const normalizeId = (val) => (val == null ? '' : String(val))

const findNodeById = (list, targetId) => {
  if (!Array.isArray(list)) return null
  for (const item of list) {
    if (normalizeId(item.id) === normalizeId(targetId)) return item
    if (Array.isArray(item.children) && item.children.length > 0) {
      const found = findNodeById(item.children, targetId)
      if (found) return found
    }
  }
  return null
}

const resolveCoursewareUrl = (data) => {
  const raw = data?.url || data?.coursewareUrl || data?.fileUrl || data?.previewUrl || ''
  if (!raw) return ''

  // 腾讯文档等外部 http(s) 链接原样透传：不加防缓存参数，避免破坏第三方嵌入
  if (/^https?:\/\//i.test(raw)) return raw

  // 兼容相对路径
  let url = raw
  if (!/^https?:\/\//i.test(url) && !url.startsWith('/')) {
    url = `/${url}`
  }

  // 避免浏览器缓存导致新课件不刷新
  const sep = url.includes('?') ? '&' : '?'
  return `${url}${sep}_t=${Date.now()}`
}

// 外部 http(s) 链接（腾讯文档嵌入）与站内 Slidev 课件分流
const isTencentDocsUrl = (url) => /^https?:\/\//i.test(url)

// 初始化数据
const initData = async () => {
  const classId = route.params.classId
  const coursewareId = route.params.coursewareId

  if (!classId) return

  try {
    const res = await getClassDirectory(classId)
    if (res.code === 200) {
      directoryTree.value = res.data || []

      if (coursewareId) {
        const realNode = findNodeById(directoryTree.value, coursewareId)
        if (realNode) {
          await handleNodeClick(realNode, false)
        } else {
          // 即使目录里暂时未找到，也尝试直接按 ID 加载一次（兼容新建后目录延迟）
          await handleNodeClick({ id: coursewareId, type: 'file' }, false)
        }

        nextTick(() => {
          treeRef.value?.setCurrentKey?.(currentId.value)
        })
      } else {
        currentId.value = null
        currentCourseware.value = { title: '', url: '' }
        problemList.value = []
      }
    } else {
      ElMessage.error(res.message || '加载课程目录失败')
    }
  } catch (e) {
    console.error('加载目录失败:', e)
    ElMessage.error('加载课程目录失败')
  }
}

const handleIframeLoad = (event) => {
  try {
    const iframe = event.target
    // 获取 iframe 内部的 document 对象
    const iframeDoc = iframe.contentDocument || iframe.contentWindow.document
    
    // 创建一个 style 标签
    const style = iframeDoc.createElement('style')
    style.innerHTML = `
      /* 精准狙击你截图中的确切 ID */
      #slidev-goto-dialog {
        display: none !important;
        opacity: 0 !important;
        visibility: hidden !important;
        pointer-events: none !important;
        z-index: -9999 !important;
      }
    `
    // 将样式强制注入到课件的 <head> 中
    iframeDoc.head.appendChild(style)
  } catch (error) {
    console.warn('向 iframe 注入样式失败，可能是跨域或加载未完成:', error)
  }
}

// 点击目录节点（加载课件 + 加载练习）
const handleNodeClick = async (data, syncRoute = true) => {
  if (!data || data.type === 'chapter') return

  currentId.value = normalizeId(data.id)
  treeRef.value?.setCurrentKey?.(currentId.value)

  try {
    const res = await getCoursewareDetail(data.id)
    if (res.code === 200 && res.data) {
      const url = resolveCoursewareUrl(res.data)
      currentCourseware.value = {
        ...res.data,
        url
      }
      iframeKey.value = `${currentId.value}-${Date.now()}`

      if (!url) {
        ElMessage.warning('课件地址为空，无法加载显示')
      }

      if (res.data.problemListId) {
        await fetchProblems(res.data.problemListId)
      } else {
        problemList.value = []
      }

      // 点击左侧目录时，同步更新 URL，保证刷新后“当前”状态不丢失
      if (syncRoute) {
        router.replace({
          params: {
            ...route.params,
            classId: route.params.classId,
            coursewareId: data.id
          }
        })
      }
    } else {
      currentCourseware.value = { title: '', url: '' }
      problemList.value = []
      ElMessage.error(res.message || '课件加载失败')
    }
  } catch (e) {
    console.error('加载课件失败:', e)
    currentCourseware.value = { title: '', url: '' }
    problemList.value = []
    ElMessage.error('加载课件失败')
  }
}

// 获取题目列表
const fetchProblems = async (listId) => {
  try {
    const res = await getListProblems({ listId })
    if (res.code === 200) {
      if (Array.isArray(res.data)) {
        problemList.value = res.data
      } else if (Array.isArray(res.data?.records)) {
        problemList.value = res.data.records
      } else {
        problemList.value = []
      }
    } else {
      problemList.value = []
    }
  } catch (e) {
    console.error('加载题目失败:', e)
    problemList.value = []
  }
}

const togglePractice = () => {
  showPractice.value = !showPractice.value
}

// 点击题目（新开页面）
const openProblem = (problem) => {
  const pid = problem.problemId || problem.id
  if (!pid) return

  const routeData = router.resolve({
    path: `/problem/${pid}`
  })
  window.open(routeData.href, '_blank')
}

// 路由参数变化时自动重载（修复“新建课件后不显示/切换不更新”）
watch(
  () => [route.params.classId, route.params.coursewareId],
  () => {
    initData()
  },
  { immediate: true }
)
</script>

<style scoped>
.study-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: var(--bg-surface-hover);
  overflow: hidden;
}

/* Header */
.study-header {
  height: 50px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-base);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  box-shadow: 0 2px 4px rgba(0,0,0,0.05);
  z-index: 10;
}
.header-left { display: flex; align-items: center; gap: 15px; }
.course-title { font-weight: 600; font-size: 16px; color: var(--text-primary); }

/* Body Layout */
.study-body {
  flex: 1;
  display: flex;
  position: relative;
  overflow: hidden;
}

/* Main Content (Slidev) */
.main-content {
  flex: 1;
  background: #000;
  display: flex;
  flex-direction: column;
  position: relative;
}
.slidev-wrapper { width: 100%; height: 100%; }
.slidev-iframe { width: 100%; height: 100%; border: none; }
.empty-state { height: 100%; display: flex; justify-content: center; align-items: center; background: var(--bg-surface); }

/* Dialog fix */
.problem-dialog :deep(.el-dialog__body) { padding: 0; height: 70vh; overflow: hidden; }
</style>
