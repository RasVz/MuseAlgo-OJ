<template>
  <el-card shadow="never" class="recommend-card">
    <template #header>
      <div class="side-header">
        <div class="header-left">
          <span class="header-icon"><el-icon><Aim /></el-icon></span>
          <div>
            <span class="header-title">目前题目推荐</span>
            <span class="header-note">按当前练习情况挑选</span>
          </div>
        </div>
        <el-button circle text aria-label="刷新题目推荐" @click="fetchRecommendations" :disabled="loading">
          <el-icon :class="{ 'is-loading': loading }"><RefreshRight /></el-icon>
        </el-button>
      </div>
    </template>

    <div class="recommend-list" v-loading="loading">
      <template v-if="recommendedProblems.length">
        <button v-for="(item, index) in recommendedProblems" :key="item.id" class="recommend-item" type="button" @click="goToProblem(item.id)">
          <span class="recommend-index">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="recommend-main">
            <span class="rec-title">{{ item.title }}</span>
            <span class="rec-meta">{{ getDifficultyLabel(item.level) }}难度</span>
          </span>
          <span class="difficulty-badge" :style="getDifficultyStyle(item.level)">
            {{ item.level }}
          </span>
          <el-icon class="item-arrow"><ArrowRight /></el-icon>
        </button>
      </template>

      <el-empty v-else description="暂无可推荐题目" :image-size="88" />
    </div>
  </el-card>
</template>

<script setup>
/**
 * 首页侧栏「题目推荐」卡片：按用户当前练习情况给出可做的题目，支持手动换一批。
 *
 * 依赖 getRecommend(userId)——推荐算法在后端，前端不参与排序，
 * 也不在前端做二次过滤，否则会与服务端的推荐口径（按知识点缺口/难度梯度）
 * 产生两套规则。这里只做展示层的三件事：
 * 1. 把后端字段名映射成组件自用的 id/title/level；
 * 2. 未登录时静默不请求，避免 401；
 * 3. 失败时清空列表并提示，让空态接管而不是保留上一次的陈旧推荐。
 *
 * 难度标签与配色的分段定义见 utils/difficulty，全站唯一来源，勿在本组件内另写阈值。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Aim, ArrowRight, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getRecommend } from '@/api/user'
import { useUserStore } from '@/stores/userStore'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const recommendedProblems = ref([])

/**
 * 拉取推荐题目。
 * 同时被 onMounted 和标题栏刷新按钮调用：手动刷新就是重新请求一次，
 * 由后端决定「换一批」的结果，前端不缓存上一批用于轮换。
 */
const fetchRecommendations = async () => {
  const userId = userStore.userId
  // 未登录直接返回且不提示：首页对游客也可见，无脑报「获取失败」属于噪声错误。
  if (!userId) return
  loading.value = true
  try {
    const res = await getRecommend(userId)
    if (res.code === 200) {
      // Array.isArray 兜底：后端成功但返回空对象时，下面 map 会直接抛错。
      const list = Array.isArray(res.data) ? res.data : []
      recommendedProblems.value = list.map(item => ({
        // 推荐接口返回的字段名是 problemId，而详情路由用的是 id，这里做一次改名对齐
        id: item.problemId,
        title: item.title,
        // ?? 800 而不是直接透传：缺失难度会渲染成「未评定」灰标，
        // 800 是分段表里的最低档（入门），兜底后至少落在合法区间内。
        level: item.difficulty ?? 800
      }))
    } else {
      // 业务码非 200：清空后由模板走空态。
      // 不能保留旧列表，否则刷新失败时用户会看到上一批推荐，误以为没有更新。
      recommendedProblems.value = []
      ElMessage.error(res.message || '获取题目推荐失败')
    }
  } catch (error) {
    recommendedProblems.value = []
    console.error('获取推荐失败', error)
    ElMessage.error('获取题目推荐失败')
  } finally {
    loading.value = false
  }
}

const goToProblem = (id) => {
  router.push({ name: 'ProblemDetail', params: { id: id } })
}

/**
 * 难度徽标的行内样式。
 * 背景色取主色 + '14'（8 位 hex 的透明度后缀）做浅色底，而不是再引一套浅色调色板——
 * 难度档位增删时这里无需同步维护。
 */
const getDifficultyStyle = (level) => {
  const color = getDifficultyColor(level)
  return { color, backgroundColor: `${color}14` }
}

onMounted(() => {
  fetchRecommendations()
})
</script>

<style scoped>
.recommend-card { overflow: hidden; border: 1px solid var(--border-light); border-radius: 8px; }
.recommend-card :deep(.el-card__header) { padding: 14px 16px 12px; border-bottom: 1px solid var(--border-light); }
.recommend-card :deep(.el-card__body) { padding: 4px 8px 8px; }

.side-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.header-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  color: var(--accent-primary);
  background: var(--accent-primary-light);
  border-radius: 7px;
  font-size: 16px;
}
.header-title {
  display: block;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 700;
}
.header-note {
  display: block;
  margin-top: 2px;
  color: var(--text-secondary);
  font-size: 11px;
}
.is-loading { animation: spin 0.8s linear infinite; }

.recommend-list { flex: 1; overflow-y: auto; }

.recommend-item {
  display: flex;
  width: 100%;
  gap: 10px;
  align-items: center;
  padding: 10px 8px;
  color: inherit;
  text-align: left;
  cursor: pointer;
  border: 0;
  border-radius: 6px;
  background: transparent;
  transition: background-color 0.16s ease;
}
.recommend-item:hover { background: var(--bg-surface-hover); }
.recommend-item:focus-visible { outline: 2px solid var(--el-color-primary-light-5); outline-offset: -2px; }

.recommend-index { flex: 0 0 24px; color: var(--text-secondary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; font-weight: 700; }
.recommend-main { display: grid; min-width: 0; flex: 1; gap: 3px; }
.rec-title { overflow: hidden; color: var(--text-primary); font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.rec-meta { color: var(--text-secondary); font-size: 11px; }
.difficulty-badge {
  flex: 0 0 auto;
  min-width: 38px;
  padding: 3px 6px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 700;
  text-align: center;
  white-space: nowrap;
}
.item-arrow { flex: 0 0 auto; color: var(--text-placeholder); font-size: 14px; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
