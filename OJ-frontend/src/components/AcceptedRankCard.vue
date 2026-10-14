<template>
  <el-card shadow="hover" class="home-card accepted-rank-card">
    <template #header>
      <div class="card-header">
        <span class="header-title"><el-icon><Trophy /></el-icon> Rating 榜 (Top 10)</span>
        <el-button text size="small" @click="$router.push({ name: 'Ranking' })">更多 →</el-button>
      </div>
    </template>

    <div class="ranking-list" v-loading="loading">
      <div v-for="(user, index) in list" :key="user.id" class="r-item">
        <div class="r-idx" :class="`top-${index + 1}`">{{ index + 1 }}</div>

        <el-avatar :size="30" :src="user.avatar" class="r-avatar">
          {{ getDisplayName(user).charAt(0).toUpperCase() }}
        </el-avatar>

        <div
          class="r-name text-truncate"
          :title="getDisplayName(user)"
          @click="goUserProfile(user.id)"
        >
          {{ getDisplayName(user) }}
        </div>

        <div class="r-score">{{ user.rating ?? 1200 }}</div>
      </div>

      <el-empty v-if="list.length === 0 && !loading" description="暂无Rating榜数据" :image-size="60" />
    </div>
  </el-card>
</template>

<script setup>
/**
 * 首页「Rating 榜 Top 10」卡片：展示全站评分最高的 10 名用户，点击昵称进入其主页。
 *
 * 数据来自 getRatingRankList(limit)，排序完全交给后端——
 * 前端不做二次排序：榜单的排名口径（含并列名次、同分排序）只有服务端有权威定义，
 * 在前端按 rating 排一遍会产生与榜单页不一致的名次。
 *
 * 卡片与失败处理：请求失败只记日志并清空列表，由模板渲染空态。
 * 首页并排展示多张同类卡片，任一张失败就弹一次全局提示会反复打断用户；
 * 空态占位同时保证卡片高度不塌陷，不会让整行栅格高低不齐。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Trophy } from '@element-plus/icons-vue'
import { getRatingRankList } from '@/api/user'

const list = ref([])
const loading = ref(false)
const router = useRouter()

/**
 * 用户展示名：昵称 > 用户名 > 单字母占位。
 * 用可选链而非直接取 user.nickname，是为了让接口字段缺失时整张卡片不白屏；
 * 模板在头像与正文处都调了它，所以必须保证任何入参都能返回字符串。
 */
const getDisplayName = (user) => {
  return String(user?.nickname || user?.username || 'U')
}

const goUserProfile = (id) => {
  router.push({ name: 'UserProfile', params: { id } })
}

/**
 * 拉取前 10 名。
 * limit 传 10 是硬编码的：卡片高度固定，多取也不会被展示，
 * 完整榜单由「更多」按钮跳转的榜单页负责。
 */
const fetchTop10 = async () => {
  loading.value = true
  try {
    const res = await getRatingRankList(10)
    if (res.code === 200) {
      // Array.isArray 兜底：成功码下返回对象/空体时，下游 v-for 拿到非数组会直接抛错。
      list.value = Array.isArray(res.data) ? res.data : []
      return
    }
    // 业务码非 200：清空走空态，不弹提示（原因见组件头）
    list.value = []
  } catch (error) {
    console.error('获取Rating榜失败', error)
    list.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchTop10()
})
</script>

<style scoped>
.home-card { border-radius: 12px; border: none; height: 100%; display: flex; flex-direction: column; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-title { font-weight: bold; font-size: 16px; display: flex; align-items: center; gap: 6px; }

.ranking-list { flex: 1; overflow-y: auto; padding-right: 5px; min-height: 300px; }
.r-item { display: flex; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.r-idx { width: 24px; text-align: center; font-weight: bold; font-family: monospace; color: var(--text-secondary); margin-right: 10px; }
.top-1 { color: var(--accent-danger); font-size: 16px; }
.top-2 { color: var(--accent-warning); font-size: 16px; }
.top-3 { color: var(--accent-primary); font-size: 16px; }

.r-avatar { margin-right: 10px; background: var(--bg-input); color: var(--text-secondary); font-size: 12px; }
.r-name { flex: 1; font-size: 14px; font-weight: 500; color: var(--text-primary); cursor: pointer; }
.r-name:hover { color: var(--accent-primary); }
.r-score { min-width: 56px; text-align: right; font-weight: bold; font-family: monospace; color: var(--accent-success); }

.text-truncate { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
