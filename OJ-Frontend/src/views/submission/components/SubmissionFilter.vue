<template>
  <NavbarContext>
    <div class="submission-navbar-filter">
      
      <div class="filter-item">
        <span class="label">题号</span>
        <el-input 
          v-model="localFilters.problemId" 
          placeholder="题目编号" 
          style="width: 140px" 
          clearable 
          @keyup.enter="triggerSearch"
          @clear="triggerSearch"
        />
      </div>

      <div class="filter-item">
        <el-select 
          v-model="localFilters.status" 
          placeholder="评测状态" 
          style="width: 160px" 
          clearable
          @change="triggerSearch"
          popper-class="status-select-dropdown" 
        >
          <el-option label="AC" :value="1">
            <span class="status-badge bg-green">AC</span>
          </el-option>

          <el-option label="WA" :value="2">
            <span class="status-badge bg-red">WA</span>
          </el-option>

          <el-option label="CE" :value="3">
            <span class="status-badge bg-yellow">CE</span>
          </el-option>

          <el-option label="RE" :value="4">
            <span class="status-badge bg-orange">RE</span>
          </el-option>

          <el-option label="TLE" :value="5">
            <span class="status-badge bg-purple">TLE</span>
          </el-option>

          <el-option label="Pending" :value="0">
            <span class="status-badge bg-gray">Pending</span>
          </el-option>
        </el-select>
      </div>

      <div class="filter-item">
        <el-select 
          v-model="localFilters.language" 
          placeholder="编程语言" 
          style="width: 140px" 
          clearable
          @change="triggerSearch"
        >
          <el-option label="C++" value="cpp" />
          <el-option label="Java" value="java" />
          <el-option label="Python" value="python" />
        </el-select>
      </div>

      <div class="divider"></div>

      <div class="scope-group">
        <el-checkbox 
          v-model="localFilters.myOnly" 
          label="只看我的" 
          @change="handleScopeChange('my')" 
        />
        <el-checkbox 
          v-model="localFilters.friendOnly" 
          label="关注的人" 
          @change="handleScopeChange('friend')" 
        />
      </div>

      <div class="action-group">
        <el-button type="primary" :icon="Search" @click="triggerSearch">查询</el-button>
        <el-button :icon="Refresh" @click="triggerReset">重置</el-button>
      </div>

    </div>
  </NavbarContext>
</template>

<script setup>
// 提交记录筛选栏：题目 / 用户 / 状态 / 语言，外加"只看我的"与"关注的人"两个范围开关。
// 后两项需要登录，且两者互斥——同时勾选在语义上无法解释"我的关注的人"到底是哪个集合。
import { ref, watch, onMounted } from 'vue'
import NavbarContext from '@/components/NavbarContext.vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus' // 引入弹窗组件
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/userStore'

const props = defineProps(['filters'])
const emit = defineEmits(['update:filters', 'search', 'reset'])
const router = useRouter()
const route = useRoute()

// 本地副本：筛选过程中不直接改 props.filters，
// 父组件重置或改路由筛选时经 watch 同步回来。
const localFilters = ref({ ...props.filters })

const isLoggedIn = () => {
  return !!useUserStore().getAccessToken()
}

// 带 redirect 回跳，登录后自动回到用户原本想看的筛选结果页
const goToLogin = () => {
  router.push({
    path: '/login',
    query: { redirect: route.fullPath }
  })
}

// 未登录时强制清掉两个范围开关：它们只能针对"我自己"生效，
// 匿名用户勾上了服务端也无从判断按谁的提交过滤。
watch(() => props.filters, (newVal) => {
  const safeFilters = { ...newVal }
  
  // 如果没登录，强制设为 false
  if (!isLoggedIn()) {
    safeFilters.myOnly = false
    safeFilters.friendOnly = false
  }
  
  localFilters.value = safeFilters
}, { deep: true })

// 首次挂载也要检查：watch 不会为初始值触发，而 URL 里可能带着
// （从别处跳回来的）myOnly 筛选条件。
onMounted(() => {
  if (!isLoggedIn() && (localFilters.value.myOnly || localFilters.value.friendOnly)) {
    localFilters.value.myOnly = false
    localFilters.value.friendOnly = false
    // 这里不需要 triggerSearch，因为初始加载通常由父组件控制
  }
})

// 只在"试图勾选"时做鉴权，取消勾选不需要登录。
// 被拦截时要手动把 v-model 改回 false：v-model 已经把值置成 true 了，
// 组件不会自动回退，不改回去界面就会显示一个"勾上了但没生效"的复选框。
const handleScopeChange = (type) => {
  const isTryingToEnable = (type === 'my' && localFilters.value.myOnly) ||
                           (type === 'friend' && localFilters.value.friendOnly)

  if (isTryingToEnable && !isLoggedIn()) {
    if (type === 'my') localFilters.value.myOnly = false
    if (type === 'friend') localFilters.value.friendOnly = false

    ElMessageBox.confirm(
      '该功能需要登录后才能使用，是否立即前往登录？',
      '提示',
      {
        confirmButtonText: '去登录',
        cancelButtonText: '暂不',
        type: 'warning',
      }
    ).then(() => {
      goToLogin()
    }).catch(() => {
      // 用户取消，什么都不做
    })

    // 关键：直接 return，不再触发搜索。否则会带着"myOnly=false 但用户以为已勾选"
    // 的状态去请求，页面看起来像筛错了。
    return
  }

  // 两个范围互斥：同时勾选在语义上无法解释
  if (type === 'my' && localFilters.value.myOnly) {
    localFilters.value.friendOnly = false
  } else if (type === 'friend' && localFilters.value.friendOnly) {
    localFilters.value.myOnly = false
  }
  
  triggerSearch()
}

// 5. 触发搜索
const triggerSearch = () => {
  emit('update:filters', localFilters.value) 
  emit('search')
}

// 重置由父组件执行：只有父组件知道筛选条件的默认值，
// 组件里再写一份默认值迟早会和父组件不一致。
const triggerReset = () => {
  emit('reset')
}
</script>

<style scoped>
/* 样式保持不变 */
.submission-navbar-filter {
  display: flex;
  align-items: center;
  height: 100%;
  gap: 15px;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.label {
  font-weight: bold;
  color: var(--text-regular);
  font-size: 14px;
  white-space: nowrap;
}

.divider {
  width: 1px;
  height: 24px;
  background-color: var(--border-light);
  margin: 0 5px;
}

.scope-group {
  display: flex;
  align-items: center;
  gap: 15px;
  white-space: nowrap;
}

.scope-group :deep(.el-checkbox) {
  margin-right: 0;
  height: auto;
}
.scope-group :deep(.el-checkbox__label) {
  padding-left: 6px;
  color: var(--text-regular);
}

.action-group {
  display: flex;
  gap: 10px;
  margin-left: 10px;
}

.status-badge {
  display: block;
  width: 100%;
  text-align: center;
  color: var(--text-on-accent);
  border-radius: 4px;       /* 圆角 */
  font-weight: 600;         /* 字体加粗 */
  font-size: 13px;
  line-height: 28px;        /* 高度 */
  margin: 3px 0;            /* 上下留点缝隙 */
  font-family: monospace;   /* 等宽字体看起来更像代码状态 */
}

.bg-green {
  background-color: var(--accent-success);
}

.bg-red {
  background-color: var(--accent-danger);
}

.bg-orange {
  background-color: var(--accent-warning);
}

.bg-yellow {
  background-color: var(--accent-warning); /* CE */
}

.bg-purple {
  background-color: var(--accent-info);
}

.bg-gray {
  background-color: var(--text-secondary); /* Pending/Unknown */
}
</style>
