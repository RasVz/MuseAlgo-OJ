<template>
  <div class="problem-list-detail">
    
    <NavBarContext v-if="problemListDetail.id">
      <div class="context-buttons">
        <el-button 
          v-if="isOwner" 
          type="primary" 
          icon="Edit" 
          round 
          @click="handleGoToEdit"
        >
          编辑题单
        </el-button>

        <el-button 
          v-if="!isOwner" 
          :type="isFavorite ? 'warning' : 'default'" 
          :icon="isFavorite ? 'StarFilled' : 'Star'" 
          plain 
          round
          @click="handleFavorite"
        >
          {{ isFavorite ? '已收藏' : '收藏' }} {{ problemListDetail.favoriteCount || 0 }}
        </el-button>

        <!-- <el-button v-if="!isOwner" type="primary" icon="CopyDocument" plain round @click="handleFork">
          转存
        </el-button> -->
      </div>
    </NavBarContext>

    <div class="content-wrapper" v-loading="loading">
      <div class="page-header">
        <div class="header-left">
          <h1 class="list-title">{{ problemListDetail.title || '加载中...' }}</h1>
          <div class="list-meta">
            <el-tag :type="problemListDetail.privacy === 1 ? 'success' : 'info'" size="small" effect="dark" class="meta-tag">
              {{ problemListDetail.privacy === 1 ? '公开' : '私有' }}
            </el-tag>
            <span class="update-time" v-if="problemListDetail.updateTime">
              更新于 {{ formatTime(problemListDetail.updateTime) }}
            </span>
          </div>
        </div>
        
        <div class="view-toggle">
          <el-radio-group v-model="currentView" size="large">
            <el-radio-button label="info">
              <el-icon class="mr-1"><DataBoard /></el-icon> 概览
            </el-radio-button>
            <el-radio-button label="problems">
              <el-icon class="mr-1"><List /></el-icon> 题目 ({{ problems.length }})
            </el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <ProblemListInfo 
        v-if="currentView === 'info' && problemListDetail.id" 
        :detail="problemListDetail" 
      />

      <div v-if="currentView === 'problems'" class="problems-view animate__animated animate__fadeIn">
        <el-card shadow="never" class="table-card">
          <ProblemTable 
            :data="problems" 
            :loading="loading" 
            :total="problems.length"
            :show-pagination="false"
          />
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup>
// 题单详情页（公开浏览视图）：概览与题目列表两个视图切换，支持收藏。
// 「我已完成多少题」是本页最想传达的信息，所以完成数在拉题目时顺带统计，
// 而不是让用户自己数——概览的环形进度条直接用它。
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { ElMessage } from 'element-plus'
import { DataBoard, List } from '@element-plus/icons-vue'
import NavBarContext from '@/components/NavbarContext.vue'
import { getProblemListDetail, getListProblems } from '@/api/problemList'

import { addFavorite, removeFavorite, checkFavorite } from '@/api/favorite'

import ProblemTable from '@/views/problem/components/ProblemTable.vue' 
import ProblemListInfo from './components/ProblemListInfo.vue' 
import dayjs from 'dayjs'

const route = useRoute()
const router = useRouter()

const listId = route.params.id
const problemListDetail = ref({})
const problems = ref([])
const loading = ref(false)
const isOwner = ref(false) 
const isFavorite = ref(false)
const currentView = ref('info') 

// 收藏体系用 targetType 区分目标种类：2=题单、1=题目。同一个收藏表靠这两个字段区分。
const TARGET_TYPE_LIST = 2

// 详情与题目并发拉取：两者互不依赖，串行会让首屏多等一个往返。
const fetchDetail = async () => {
  loading.value = true
  try {
    const [detailRes, problemsRes] = await Promise.all([
      getProblemListDetail(listId),
      // 题目列表接口不传 userId，userStatus 由后端从 token 里解析出的当前用户算出；
      // 传了反而会因为前端 store 里的 userId 尚未恢复（刷新瞬间）而查错人。
      getListProblems({ listId: listId }) 
    ])

    if (detailRes.data) {
      problemListDetail.value = detailRes.data
      
      const currentUserId = useUserStore().userId
      isOwner.value = (currentUserId === detailRes.data.creatorId)
      if (currentUserId) {
        try {
          const favRes = await checkFavorite({ 
            targetId: listId, 
            targetType: TARGET_TYPE_LIST 
          })
          if (favRes.code === 200) {
            isFavorite.value = favRes.data 
          }
        } catch (e) {
          console.error('获取收藏状态失败', e)
        }
      }
    }

    if (problemsRes.data) {
      const rawList = problemsRes.data
      
      // 完成数在遍历题目时顺带累加：接口不给这个统计，
      // 多发一次请求只为一个数字不值得。
      let currentSolvedCount = 0 

      problems.value = rawList.map(item => {
        // 1. 处理标签
        let tagsArr = []
        try {
          if (item.tags) {
            tagsArr = typeof item.tags === 'string' ? JSON.parse(item.tags) : item.tags
          }
        } catch { 
          tagsArr = [item.tags] 
        }

        // userStatus：1=已通过、2 及以上=尝试过未通过；题单内的"完成度"只认前者。
        let statusStr = ''
        if (item.userStatus === 1) {
          statusStr = 'pass'
          currentSolvedCount++ 
        } else if (item.userStatus === 2 || (item.userStatus > 0)) {
          statusStr = 'tried'
        }

        // 通过率在这里算好塞进行数据：表格只负责渲染，不该知道分子分母从哪来。
        const submitCount = item.submitCount || item.totalCount || 0
        const passCount = item.passCount || 0
        const acceptanceRate = submitCount > 0 
          ? ((passCount / submitCount) * 100).toFixed(1) + '%' 
          : '0.0%'

        return {
          ...item,
          tags: tagsArr,
          status: statusStr,
          // === 把算好的总提交数和通过率塞进对象，表格就能正常展示了 ===
          totalCount: submitCount,
          acceptance: acceptanceRate,
        }
      })
      
      // 完成数与题目总数写回详情，概览页的环形进度条直接读这两个值。
      if (problemListDetail.value) {
        problemListDetail.value.problemCount = problems.value.length
        problemListDetail.value.solvedCount = currentSolvedCount
      }
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('获取数据失败')
  } finally {
    loading.value = false
  }
}

const handleGoToEdit = () => { router.push(`/problem/list/${listId}/edit`) }

// 收藏是"乐观更新"：请求成功后立刻本地取反并同步收藏数，不重新拉详情。
// 这里取反而不是重查是安全的——后端收藏表对同一 (user,target) 有唯一约束，
// 不会出现两个并发请求把状态搞反；但如果改成加减计数就要小心并发覆盖。
const handleFavorite = async () => {
  if (!useUserStore().userId) {
    ElMessage.warning('请先登录后再操作')
    return
  }

  try {
    let res = null;

    // 已收藏则取消，未收藏则新增：收藏接口是 toggle 语义的两条独立路径
    if (isFavorite.value) {
      res = await removeFavorite({
        targetId: listId,
        targetType: TARGET_TYPE_LIST
      })
    } else {
      res = await addFavorite({
        targetId: listId,
        targetType: TARGET_TYPE_LIST
      })
    }

    if (res.code === 200) {
      isFavorite.value = !isFavorite.value

      // 本地同步收藏数，避免为增减 1 而重拉整个详情
      const change = isFavorite.value ? 1 : -1
      problemListDetail.value.favoriteCount = (problemListDetail.value.favoriteCount || 0) + change
      
      ElMessage.success(isFavorite.value ? '收藏成功' : '已取消收藏')
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('操作失败，请重试')
  }
}


const formatTime = (time) => dayjs(time).format('YYYY-MM-DD HH:mm')

onMounted(() => { fetchDetail() })
</script>

<style scoped>
.problem-list-detail {
  min-height: calc(100vh - 60px);
  background-color: var(--bg-app);
  padding-bottom: 40px;
}

.content-wrapper {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
}

.context-buttons { display: flex; align-items: center; gap: 12px; }

/* Header & Toggle */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.list-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 8px 0;
}

.list-meta { display: flex; align-items: center; gap: 12px; }
.update-time { font-size: 13px; color: var(--text-secondary); }
.mr-1 { margin-right: 4px; }

/* Problems View Card */
.table-card {
  border-radius: 8px;
  border: none;
}

.table-card :deep(.el-card__body) {
  padding: 0;
}
</style>
