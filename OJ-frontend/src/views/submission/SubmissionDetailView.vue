<template>
  <div class="submission-detail-view">
    <NavbarContext>
      <div class="nav-header">
        <el-page-header @back="goBack" icon="ArrowLeft" title="返回">
          <template #content>
            <span class="nav-title">提交详情 #{{ route.params.id }}</span>
          </template>
        </el-page-header>
      </div>
    </NavbarContext>

    <div class="main-content" v-loading="loading">
      <div class="detail-container" v-if="detail">
        
        <SubmissionStatusBanner
          :detail="detail"
          :can-review="canReview"
          :reviewing="reviewing"
          @review="reviewDialogVisible = true"
        />

        <div v-if="isInProgress" class="judge-progress mb-4">
          <div class="judge-progress-title">系统正在处理本次提交</div>
          <el-steps :active="judgeStep" align-center finish-status="success" process-status="process">
            <el-step title="排队中" description="等待判题机" />
            <el-step title="编译中" description="检查代码能否运行" />
            <el-step title="判题中" description="运行测试数据" />
          </el-steps>
        </div>

        <div class="info-row mb-4">
          <el-descriptions :column="3" border class="info-desc">
            <el-descriptions-item label="题目">
              <el-link type="primary" :underline="false" @click="router.push({ path: `/problem/${detail.problemId}` })">
                P{{ detail.problemId }} {{ detail.problemTitle || '' }}
              </el-link>
            </el-descriptions-item>
            <el-descriptions-item label="提交者">
              <el-link type="primary" :underline="false" @click="router.push({ path: `/user/${detail.userId}` })">
                {{ detail.username || detail.userId }}
              </el-link>
            </el-descriptions-item>
            <el-descriptions-item label="提交时间">
              {{ formatTime(detail.createTime) }}
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <div v-if="detail.status !== 1 && detail.errorMessage" class="section-block mb-4">
          <div class="error-panel">
            <div class="panel-header error">
              <div class="header-left">
                <el-icon><Warning /></el-icon>
                <span>错误详情 / 编译器输出</span>
              </div>
            </div>
            <div class="error-code-block">
              <pre>{{ detail.errorMessage }}</pre>
            </div>
          </div>
        </div>

        <div v-if="failureAdvice" class="failure-advice section-block mb-4">
          <el-icon><InfoFilled /></el-icon><div><strong>下一步建议</strong><p>{{ failureAdvice }}</p></div>
        </div>

        <div class="section-block">
          <div class="code-panel">
            <div class="panel-header">
              <div class="header-left">
                <el-icon><Document /></el-icon>
                <span>源代码</span>
                <el-tag size="small" effect="plain" class="ml-2">{{ detail.language }}</el-tag>
              </div>
              <el-button v-if="detail.code" size="small" type="primary" link @click="copyCode">
                <el-icon class="mr-1"><CopyDocument /></el-icon> 复制代码
              </el-button>
            </div>
            
            <div class="code-content">
              <pre v-if="detail.code"><code :class="`language-${getLanguageClass(detail.language)}`" ref="codeBlock">{{ detail.code }}</code></pre>
              <div v-else style="padding: 20px; color: var(--text-secondary); text-align: center;">暂无代码或无权限查看</div>
            </div>
          </div>
        </div>

      </div>

      <div v-else-if="!loading" class="empty-state">
        <el-empty description="未找到记录或加载失败" />
      </div>
    </div>

    <AiReviewDialog
      v-model:visible="reviewDialogVisible"
      :submission-id="route.params.id"
      :problem-id="detail?.problemId ?? null"
    />
  </div>
</template>

<script setup>
// 提交详情页：展示判题结果、源码与错误信息，并对判题中的提交自动轮询。
//
// 轮询用递归 setTimeout 而不是 setInterval：每次请求返回后才安排下一次，
// 请求慢时不会堆积，也不会有"上一轮还没回来下一轮已发出"的竞态。
// 一旦拿到终态（status != 0）或判题服务异常（judgePhase=FAILED）就停止轮询。
import { computed, ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSubmissionDetail } from '@/api/submission' 
import { ElMessage } from 'element-plus'
import { 
  InfoFilled, Warning, Document, CopyDocument
} from '@element-plus/icons-vue'
import NavbarContext from '@/components/NavbarContext.vue'
import SubmissionStatusBanner from './components/SubmissionStatusBanner.vue'
import AiReviewDialog from './components/AiReviewDialog.vue'
import hljs from '@/utils/highlight'
import 'highlight.js/styles/atom-one-light.css'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref(null)
const codeBlock = ref(null)
let pollTimer

// AI 复盘弹窗（逻辑已迁入 AiReviewDialog）
const reviewDialogVisible = ref(false)
const reviewing = ref(false)

// 只对"判过但没过"的提交开放 AI 复盘：AC 的没什么可复盘，
// 编译错误（3）虽然没过但 AI 也帮不上什么实际的忙，这里仍开放是为了覆盖代码本身写错的情况。
const canReview = computed(() => [2, 3, 4, 5].includes(Number(detail.value?.status)))

const loadData = async () => {
  const id = route.params.id
  if (!id) return
  loading.value = true
  try {
    const res = await getSubmissionDetail(id)
    if (res.code === 200) {
      detail.value = res.data
      if (isInProgress.value) schedulePoll()
      // 必须等 DOM 渲染出 code 元素再高亮：nextTick 前 codeBlock 还是 null，
// 直接调用 hljs 会静默失败，页面只剩一段无颜色的代码。
      nextTick(() => {
        if (codeBlock.value && detail.value?.code) {
          hljs.highlightElement(codeBlock.value)
        }
      })
    } else {
      ElMessage.error(res.message || '获取详情失败')
    }
  } catch (e) {
    if (e?.response?.data?.code === 403) {
      // 无权限查看（如他人的提交源码）：拦截器已提示原因，直接回到提交记录页
      router.replace({ name: 'Submission' })
    } else {
      console.error(e)
      ElMessage.error('网络错误')
    }
  } finally {
    loading.value = false
  }
}

const goBack = () => {
  // 直达链接/新标签页打开时 history 无上级页面，回退到提交记录列表
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push({ name: 'Submission' })
  }
}

const copyCode = () => {
  if (detail.value?.code) {
    navigator.clipboard.writeText(detail.value.code)
    ElMessage.success('代码已复制')
  }
}

// status=0 且判题阶段不是 FAILED 才算"进行中"：
// FAILED 表示判题服务挂了，再轮询一万次也不会变，只会白白消耗。
const isInProgress = computed(() => detail.value && Number(detail.value.status) === 0 && detail.value.judgePhase !== 'FAILED')

// judgePhase 映射成进度条步骤序号：QUEUED → COMPILING → JUDGING，
// 未识别的阶段一律当作第 0 步，而不是让进度条显示 NaN。
const judgeStep = computed(() => ({ QUEUED: 0, COMPILING: 1, JUDGING: 2 }[detail.value?.judgePhase] ?? 0))

// 各类错误的排查建议：判题只给一个结果码，学生往往不知道下一步该看哪里。
const failureAdvice = computed(() => ({
  2: '请检查边界条件、初始化与输出格式；用最小样例手动推演一遍通常最有效。',
  3: '请先阅读编译器输出，重点检查拼写、类型、缺失的头文件或括号配对。',
  4: '请检查数组或下标是否越界、除数是否为 0，以及递归是否可能栈溢出。',
  5: '请检查算法复杂度；尝试减少重复计算，或改用更高效的数据结构与算法。'
}[Number(detail.value?.status)] || ''))
// 每次 setTimeout 前先 clearTimeout：loadData 成功后又会安排下一次，
// 不清就会累积出指数增长的定时器，把接口打爆。
const schedulePoll = () => { clearTimeout(pollTimer); pollTimer = setTimeout(loadData, 1500) }

// 语言名到 highlight.js 类名的模糊匹配：后端可能返回 "C++17"、"cpp"、"C++" 等各种写法，
// 都归到同一套高亮规则上，避免高亮失效退回纯文本。
const getLanguageClass = (lang) => {
  if (!lang) return 'plaintext'
  const lower = lang.toLowerCase()
  if (lower.includes('c++') || lower.includes('cpp')) return 'cpp'
  if (lower.includes('java')) return 'java'
  if (lower.includes('python')) return 'python'
  return 'plaintext'
}

const formatTime = (t) => t ? t.replace('T', ' ').substring(0, 19) : '-'

onMounted(() => loadData())
// 卸载时必须清掉待执行的轮询定时器，否则组件已销毁仍会发请求并写状态。
onUnmounted(() => clearTimeout(pollTimer))
</script>

<style scoped>
.submission-detail-view {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px);
  overflow: hidden;
}
.nav-header {
  display: flex;
  align-items: center;
  height: 100%;
}
.nav-title {
  font-weight: 600;
  font-size: 16px;
  color: var(--text-primary);
}
.main-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background-color: var(--bg-app);
}
.detail-container {
  max-width: 1000px;
  margin: 0 auto;
}
.mb-4 { margin-bottom: 20px; }
.ml-2 { margin-left: 8px; }
.mr-1 { margin-right: 4px; }
.judge-progress { padding: 20px; border: 1px solid var(--el-color-primary-light-7); border-radius: 8px; background: var(--accent-primary-light); }
.judge-progress-title { margin-bottom: 16px; color: var(--accent-primary-dark); font-weight: 650; }
.failure-advice { display:flex; gap:10px; padding:15px 18px; border:1px solid var(--el-color-warning-light-7); border-radius:8px; background:var(--accent-warning-light); color:var(--el-color-warning-dark-2); }
.failure-advice p { margin:6px 0 0; line-height:1.65; font-size:14px; }
.info-desc {
  background: var(--bg-surface);
  padding: 10px;
  border-radius: 8px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background-color: var(--bg-surface);
  border-bottom: 1px solid var(--el-border-color-lighter);
  border-radius: 8px 8px 0 0;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: bold;
  color: var(--text-primary);
}
.error-panel {
  border: 1px solid var(--el-color-danger-light-7);
  border-radius: 8px;
  overflow: hidden;
}
.error-panel .panel-header.error {
  background-color: var(--accent-danger-light);
  color: var(--accent-danger);
  border-bottom-color: var(--el-color-danger-light-7);
}
.error-code-block {
  padding: 15px;
  background-color: var(--bg-surface);
  color: var(--accent-danger);
  font-family: Consolas, monospace;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
}
.code-panel {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
  background: var(--bg-surface);
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}
.code-content {
  margin: 0;
  padding: 0;
  background-color: var(--bg-surface-hover);
  overflow-x: auto;
}
.code-content pre {
  margin: 0;
  padding: 15px;
  font-family: 'Fira Code', Consolas, monospace;
  font-size: 14px;
  line-height: 1.5;
}
.hljs {
  background: transparent !important;
  padding: 0 !important;
}
</style>
