<template>
  <div class="my-lists-container">
    
    <NavbarContext>
      <div class="navbar-title-section">
        <h1 class="page-title">我的题单</h1>
        <span class="sub-title">管理你的题目合集</span>
      </div>
    </NavbarContext>

    <div class="filter-section">
       <div class="filter-bar">
        <el-input 
          v-model="searchKeyword" 
          placeholder="搜索题单..." 
          prefix-icon="Search"
          class="search-input"
          clearable
          @keyup.enter="fetchData"
        />
        
        <el-radio-group v-model="filterType" class="ml-4" @change="fetchData">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button label="public">公开</el-radio-button>
          <el-radio-button label="private">私有</el-radio-button>
        </el-radio-group>

        <div class="action-box">
          <el-button type="primary" icon="Plus" @click="handleCreate">
            新建题单
          </el-button>
        </div>
      </div>
    </div>

    <div class="table-container">
      <el-card shadow="never" class="main-card" :body-style="{ padding: '0', height: '100%', display: 'flex', flexDirection: 'column' }">
        <el-table 
          :data="listData" 
          style="width: 100%; flex: 1;" 
          height="100%" 
          v-loading="loading"
          :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)', fontWeight: 'bold' }"
          row-key="id"
          @row-click="goDetail"
        >
          <el-table-column label="题单名称" min-width="300">
            <template #default="{ row }">
              <div class="list-title-wrapper">
                <div class="list-icon-box" :class="{ 'is-private': row.privacy === 0 }">
                  <el-icon v-if="row.privacy === 0"><Lock /></el-icon>
                  <el-icon v-else><List /></el-icon>
                </div>
                <div class="list-info">
                  <div class="list-name">
                    {{ row.title }}
                    <el-tag v-if="row.privacy === 0" type="info" size="small" effect="plain" class="private-tag">私有</el-tag>
                  </div>
                  <div class="list-desc text-truncate">{{ row.description || '暂无简介' }}</div>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="problemCount" label="题目数" width="120" align="center">
            <template #default="{ row }">
              <span class="mono-font">{{ row.problemCount || 0 }}</span>
            </template>
          </el-table-column>
          
          <el-table-column prop="favorites" label="收藏" width="100" align="center">
            <template #default="{ row }">
               <span class="mono-font text-secondary">{{ row.favoriteCount }}</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.privacy === 1" type="success" effect="light">公开</el-tag>
              <el-tag v-else type="info" effect="light">私有</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="updateTime" label="最后更新" width="180" align="right">
            <template #default="{ row }">
              <span class="text-secondary text-small">{{ row.updateTime }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="140" align="center" fixed="right">
            <template #default="{ row }">
              <el-button 
                type="primary" 
                link 
                icon="Edit"
                @click.stop="openEditPage(row)"
              >
                编辑题单
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <div class="pagination-footer">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<script setup>
// 我的题单：列出当前用户创建的个人题单，支持公开/私有筛选与搜索。
// 查询按 creatorId + type=1 锁定在"我创建的"范围内，不依赖后端再过滤——
// 否则用户能看到别人题单的数量会随数据量膨胀。
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import NavbarContext from '@/components/NavbarContext.vue'
import { Lock, List } from '@element-plus/icons-vue'
import { getProblemLists } from '@/api/problemList'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/userStore'

const router = useRouter()
const loading = ref(false)
const listData = ref([])
const searchKeyword = ref('')
const filterType = ref('all')
const currentPage = ref(1)
const pageSize = 10
const total = ref(0)

const getUserId = () => useUserStore().userId

// userId 未就绪时直接返回：store 在刷新后需要异步恢复，
// 此时发请求会拿到别人的题单或空列表。
const fetchData = async () => {
  const uid = getUserId()
  if (!uid) return
  loading.value = true
  try {
    const params = {
      page: currentPage.value,
      size: pageSize,
      creatorId: uid,
      keyword: searchKeyword.value,
      type: 1
    }
    // filterType 为 all 时不传 privacy，而不是传 -1：
// 后端把 privacy 当 0/1 判定，传非枚举值会退化成"查不到"或"查全部"的歧义状态。
    if (filterType.value === 'public') params.privacy = 1
    if (filterType.value === 'private') params.privacy = 0

    const res = await getProblemLists(params)
    if (res.code === 200) {
      // updateTime 可能为空（从未改过内容的新题单），回退到 createTime 显示，
// 否则列表里会整列空白。
      listData.value = res.data.records.map(item => ({
        ...item,
        favoriteCount: item.favoriteCount || 0,
        updateTime: formatTime(item.updateTime || item.createTime)
      }))
      total.value = res.data.total
    }
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

const formatTime = (time) => time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
const handlePageChange = (val) => { currentPage.value = val; fetchData() }

// 新建用 'create' 作为路径占位符而非真实 ID，编辑页据此进入新建模式。
const handleCreate = () => {
  router.push(`/problem/list/create/edit`)
}

const openEditPage = (row) => {
  router.push(`/problem/list/${row.id}/edit`)
}

const goDetail = (row) => {
  if (row && row.id) {
    router.push(`/problem/list/${row.id}`)
  }
}

onMounted(fetchData)
</script>

<style scoped>
.my-lists-container { 
  height: 100%;       
  display: flex; 
  flex-direction: column;
  box-sizing: border-box;
  padding: 10px 20px 0 20px;
  overflow: hidden;   
  background-color: var(--bg-app);
}

.navbar-title-section {
  display: flex;
  align-items: center;
  gap: 10px;
}
.page-title { margin: 0; font-size: 18px; color: var(--text-primary); font-weight: 600; }
.sub-title { color: var(--text-secondary); font-size: 12px; margin-top: 2px; }

.filter-section {
  padding: 10px 0;
  flex-shrink: 0;
}
.filter-bar { 
  display: flex; 
  align-items: center; 
}

.action-box {
  margin-left: auto; 
}

.search-input { width: 250px; }
.ml-4 { margin-left: 16px; }

.table-container {
  flex: 1;            
  overflow: hidden;   
  margin-bottom: 10px;
  background-color: var(--bg-surface);
  border-radius: 8px;
}
.main-card {
  height: 100%;
  border: none;
  border-radius: 8px;
}

.pagination-footer {
  flex-shrink: 0;
  height: 50px;
  display: flex; 
  justify-content: center;
  align-items: center;
  background-color: var(--bg-surface);
  border-top: 1px solid var(--border-light);
  border-radius: 8px 8px 0 0;
}

.list-title-wrapper { display: flex; align-items: center; gap: 15px; padding: 5px 0; }
.list-icon-box {
  width: 42px; height: 42px; border-radius: 10px;
  background: linear-gradient(135deg, #a0cfff 0%, #409eff 100%);
  color: var(--text-on-accent); font-size: 20px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.list-icon-box.is-private { background: var(--bg-surface-hover); color: var(--text-secondary); }
.list-info { flex: 1; overflow: hidden; }
.list-name { font-weight: bold; font-size: 15px; color: var(--text-primary); display: flex; align-items: center; gap: 8px; }
.private-tag { height: 18px; line-height: 16px; padding: 0 4px; }
.list-desc { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
.text-truncate { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mono-font { font-family: 'Menlo', monospace; font-weight: 600; }
.text-secondary { color: var(--text-secondary); }
.text-small { font-size: 13px; }
</style>
