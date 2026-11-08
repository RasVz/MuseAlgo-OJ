<template>
  <el-card shadow="hover" class="home-card announcement-card">
    <template #header>
      <div class="card-header">
        <span class="header-title"><el-icon><Bell /></el-icon> 系统公告</span>
        <el-link type="primary" underline="never" @click="$router.push({ name: 'Announcement' })">全部 ></el-link>
      </div>
    </template>
    <div class="notice-list" v-loading="loading">
      <div v-for="item in list" :key="item.id" class="n-item" @click="$router.push({ name: 'PostDetail', params: { id: item.id } })">
        <span class="dot"></span>
        <span class="n-title">{{ item.title }}</span>
        <span class="n-time">{{ formatTime(item.createTime) }}</span>
      </div>
      <el-empty v-if="!loading && list.length === 0" description="暂无公告" :image-size="50" />
    </div>
  </el-card>
</template>

<script setup>
/**
 * 首页「系统公告」卡片：展示最新的 3 条公告，点击进入帖子详情。
 *
 * 数据来自 getAnnouncements（专用的公告接口），与讨论列表用同一个 getPostList 但不同数据源——
 * 讨论卡片在客户端过滤 category 剔除公告，公告卡片则直接读公告接口，
 * 避免「首页先取全量再各自过滤」造成的重复请求。
 *
 * 排序（含置顶公告置前）由后端完成，本组件不做重排：
 * 置顶规则只有服务端有权威定义（置顶权重、置顶是否计入时间倒序），
 * 在前端按 createTime 排一遍会把置顶公告打回原位。
 */
import { ref, onMounted } from 'vue'
import { getAnnouncements } from '@/api/post'
import dayjs from 'dayjs'
import { Bell } from '@element-plus/icons-vue'

const list = ref([])
const loading = ref(false)

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getAnnouncements()
    // 只展示 3 条：首页卡片高度有限，多取也不会被看到，
    // 完整列表由「全部」入口跳转的公告页负责。
    if (res.code === 200) list.value = res.data.slice(0, 3)
  } finally { loading.value = false }
}

// 与讨论卡片一致只显示月日：公告的时效性靠排序体现，不需要在卡片上占宽度显示年份
const formatTime = (t) => dayjs(t).format('MM-DD')

onMounted(fetchData)
</script>

<style scoped>
.home-card { border-radius: 12px; border: none; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-title { font-weight: bold; font-size: 16px; display: flex; align-items: center; gap: 6px; }

.n-item { display: flex; align-items: center; padding: 8px 0; cursor: pointer; color: var(--text-regular); font-size: 14px; }
.n-item:hover { color: var(--accent-primary); }
.dot { width: 6px; height: 6px; background: var(--accent-warning); border-radius: 50%; margin-right: 8px; flex-shrink: 0; }
.n-title { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-right: 10px; }
.n-time { font-size: 12px; color: var(--text-secondary); flex-shrink: 0; }
</style>
