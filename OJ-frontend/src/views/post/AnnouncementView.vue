<template>
  <div class="announcement-page">
    <el-card shadow="never" class="list-card">
      <template #header>
        <div class="page-header">
          <h2>📢 系统公告</h2>
          <span class="subtitle">这里发布平台的重要通知与更新日志</span>
        </div>
      </template>

      <div class="timeline-box" v-loading="loading">
        <el-timeline v-if="list.length > 0">
          <el-timeline-item
            v-for="(item, index) in list"
            :key="item.id"
            :timestamp="formatTime(item.createTime)"
            placement="top"
            :type="index === 0 ? 'primary' : ''" 
            :hollow="index === 0"
          >
            <el-card shadow="hover" class="post-card" @click="viewDetail(item.id)">
              <h4>{{ item.title }}</h4>
              <p class="summary">{{ item.content ? item.content.substring(0, 100) + '...' : '无内容摘要' }}</p>
              <div class="footer">
                <span class="author">发布人: 管理员</span>
                <span><el-icon><View /></el-icon> {{ item.views || 0 }} 次阅读</span>
                <el-button type="primary" link>查看详情</el-button>
              </div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无公告" />
      </div>
      
      <div class="load-more" v-if="list.length > 0">
        <span class="no-more">已加载全部公告</span>
      </div>
    </el-card>
  </div>
</template>

<script setup>
// 公告列表页：展示全站公告，置顶的排在前面。
// 公告本身复用帖子存储，点击后跳到 PostDetail 查看完整内容。
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getAnnouncements } from '@/api/post'

const router = useRouter()
const list = ref([])
const loading = ref(false)

// 获取公告列表
const fetchAnnouncements = async () => {
  loading.value = true
  try {
    const res = await getAnnouncements()
    if (res.code === 200) {
      list.value = res.data
    }
  } catch (error) {
    console.error('获取公告失败', error)
  } finally {
    loading.value = false
  }
}

const formatTime = (val) => {
  if (!val) return ''
  return val.replace('T', ' ').substring(0, 10) // 仅显示日期
}

const viewDetail = (id) => {
  router.push({ name: 'PostDetail', params: { id: id } })
}

onMounted(() => {
  fetchAnnouncements()
})
</script>

<style scoped>
.announcement-page {
  max-width: 900px;
  margin: 0 auto;
}
.list-card {
  border-radius: 12px;
  border: none;
  min-height: 80vh;
}
.page-header h2 { margin: 0 0 10px 0; color: var(--text-primary); }
.subtitle { color: var(--text-secondary); font-size: 14px; }

.timeline-box {
  padding: 40px 20px 0;
}

.post-card {
  cursor: pointer;
  border-radius: 8px;
  transition: transform 0.2s;
}
.post-card:hover {
  transform: translateX(5px);
}
.post-card h4 { margin: 0 0 10px; font-size: 16px; color: var(--text-primary); }
.summary { color: var(--text-regular); font-size: 14px; line-height: 1.6; margin-bottom: 15px; }
.footer { display: flex; justify-content: space-between; font-size: 12px; color: var(--text-secondary); }

.load-more { text-align: center; margin-top: 20px; padding-bottom: 20px; }
.no-more { color: var(--text-placeholder); font-size: 13px; }
</style>