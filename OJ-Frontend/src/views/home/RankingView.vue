<template>
  <div class="rank-list-container">
    
    <NavbarContext>
      <div class="navbar-title">
        <el-icon class="mr-2"><Trophy /></el-icon>
        全站排名
      </div>
    </NavbarContext>

    <el-card shadow="never" class="main-card">
      
      <div class="filter-bar">
        <div class="left-tip">
          <el-icon><InfoFilled /></el-icon>
          <span>排名基于 Rating 积分计算，每日凌晨自动更新。</span>
        </div>
        <div class="right-search">
          <el-input 
            v-model="searchKeyword" 
            placeholder="搜索用户名..." 
            prefix-icon="Search"
            style="width: 240px"
            clearable
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-button type="primary" @click="handleSearch">搜索</el-button>
        </div>
      </div>

      <div class="table-scroll-area">
        <el-table 
          :data="userList" 
          style="width: 100%" 
          v-loading="loading" 
          stripe
          :header-cell-style="{ background: 'var(--bg-input)', color: 'var(--text-regular)', fontWeight: 'bold' }"
        >
          <el-table-column label="排名" width="100" align="center">
            <template #default="{ row, $index }">
              <div class="rank-cell">
                <img v-if="(row.rank || getRealRank($index)) === 1" src="https://cdn-icons-png.flaticon.com/512/2583/2583344.png" class="medal-icon" alt="Gold" />
                <img v-else-if="(row.rank || getRealRank($index)) === 2" src="https://cdn-icons-png.flaticon.com/512/2583/2583319.png" class="medal-icon" alt="Silver" />
                <img v-else-if="(row.rank || getRealRank($index)) === 3" src="https://cdn-icons-png.flaticon.com/512/2583/2583434.png" class="medal-icon" alt="Bronze" />
                <span v-else class="rank-num">{{ row.rank || getRealRank($index) }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="用户" min-width="150">
            <template #default="{ row }">
              <div class="user-info-cell" @click="goUserProfile(row.id)">
                <el-avatar :size="36" :src="row.avatar" class="user-avatar">
                  {{ row.username }}
                </el-avatar>
                <div class="name-box">
                  <span class="username" :class="getRatingColorClass(row.rating)">
                    {{ row.username }}
                  </span>
                  <span class="motto text-truncate">{{ row.motto || '这家伙很懒，什么也没写' }}</span>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="学校 / 机构" min-width="150" prop="school" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.school || '-' }}
            </template>
          </el-table-column>

          <el-table-column label="AC 数" width="120" align="center" sortable prop="accepted">
            <template #default="{ row }">
              <span class="ac-count">{{ row.acceptedCount }}</span>
            </template>
          </el-table-column>
          
          <el-table-column label="Rating" width="150" align="center" sortable prop="rating">
            <template #default="{ row }">
              <span class="rating-badge" :style="{ background: getRatingColor(row.rating) }">
                {{ row.rating }}
              </span>
            </template>
          </el-table-column>

        </el-table>
      </div>

      <div class="pagination-footer">
        <el-pagination 
          background 
          layout="prev, pager, next, total, sizes, jumper" 
          :total="total" 
          :page-sizes="[20, 50, 100]"
          v-model:page-size="pageSize"
          v-model:current-page="currentPage"
          @size-change="fetchData"
          @current-change="fetchData"
        />
      </div>

    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Trophy, InfoFilled } from '@element-plus/icons-vue'
import NavbarContext from '@/components/NavbarContext.vue'
import { getRankList } from '@/api/rank' // 引入 API

const router = useRouter()
const loading = ref(false)
const userList = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(50) // 默认查 Top 50
const searchKeyword = ref('')

// --- 获取数据逻辑 ---
const fetchData = async () => {
  loading.value = true
  try {
    // 调用后端接口
    // 注意：目前的 RankController 接收的是 limit 参数
    // 如果想要搜索功能，需要在后端 RankController 增加搜索逻辑
    const res = await getRankList(pageSize.value)
    
    if (res.code === 200) {
      let data = res.data
      
      // 前端临时搜索过滤（建议后续移至后端）
      if (searchKeyword.value) {
        data = data.filter(u => 
          u.username && u.username.toLowerCase().includes(searchKeyword.value.toLowerCase())
        )
      }

      userList.value = data
      // 因为 Redis 榜单通常是 Top N，这里 total 暂时等于列表长度
      total.value = data.length 
    }
  } catch (error) {
    console.error('获取排行榜失败', error)
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  fetchData()
}

// 计算排名（如果后端 VO 里没带 rank 字段，用这个计算）
const getRealRank = (index) => {
  return (currentPage.value - 1) * pageSize.value + index + 1
}

const goUserProfile = (id) => {
  router.push({ name: 'UserProfile', params: { id } })
}

// --- Rating 颜色逻辑 (保持不变) ---
const getRatingColor = (rating) => {
  if (rating >= 2400) return '#ff3333' 
  if (rating >= 2100) return '#ffbb55' 
  if (rating >= 1900) return '#aa00aa' 
  if (rating >= 1600) return '#0000ff' 
  if (rating >= 1400) return '#03a89e' 
  if (rating >= 1200) return '#008000' 
  return '#808080' 
}

const getRatingColorClass = (rating) => {
  if (rating >= 2400) return 'color-red'
  if (rating >= 2100) return 'color-orange'
  if (rating >= 1900) return 'color-violet'
  if (rating >= 1600) return 'color-blue'
  if (rating >= 1400) return 'color-cyan'
  if (rating >= 1200) return 'color-green'
  return 'color-gray'
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
/* 你的样式完全保持不变 */
.rank-list-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 85px - 40px);
  overflow: hidden;
}

.navbar-title {
  font-size: 18px;
  font-weight: bold;
  color: var(--text-primary);
  display: flex;
  align-items: center;
}
.mr-2 { margin-right: 8px; }

.main-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  border: none;
  min-height: 0;
}

.main-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0;
  min-height: 0;
  overflow: hidden;
}

.filter-bar {
  padding: 15px 20px;
  border-bottom: 1px solid var(--border-light);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}
.left-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.right-search {
  display: flex;
  gap: 10px;
}

.table-scroll-area {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}

.rank-cell {
  display: flex;
  justify-content: center;
  align-items: center;
}
.medal-icon { width: 24px; height: 24px; }
.rank-num { font-weight: bold; color: var(--text-regular); font-family: 'Menlo', monospace; }

.user-info-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}
.user-avatar {
  flex-shrink: 0;
  background: var(--bg-input);
  color: var(--text-secondary);
  font-weight: bold;
}
.name-box {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.username { font-weight: bold; font-size: 14px; }
.motto { font-size: 12px; color: var(--text-secondary); }
.text-truncate { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px; }

.ac-count { font-family: monospace; color: var(--text-regular); font-weight: 600; }

.rating-badge {
  color: var(--text-on-accent);
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: bold;
  min-width: 40px;
  display: inline-block;
  text-shadow: 0 1px 1px rgba(0,0,0,0.2);
}

.color-red { color: #ff3333 !important; }
.color-orange { color: #ffbb55 !important; }
.color-violet { color: #aa00aa !important; }
.color-blue { color: #0000ff !important; }
.color-cyan { color: #03a89e !important; }
.color-green { color: #008000 !important; }
.color-gray { color: #808080 !important; }

.pagination-footer {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  border-top: 1px solid var(--border-light);
  background: var(--bg-surface);
}
</style>