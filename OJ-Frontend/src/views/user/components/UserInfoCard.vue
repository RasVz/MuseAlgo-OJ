<template>
  <el-card shadow="never" class="info-card-sticky allow-overflow">
    <div class="avatar-wrapper">
      <el-avatar 
        :size="100" 
        :src="user.avatar || defaultAvatar" 
        class="user-avatar"
      />
    </div>
    
    <div class="user-identity">
      <h2 class="nickname">{{ user.nickname || user.username || '未命名用户' }}</h2>
      <div class="username-row">
        <span class="username">@{{ user.username || 'user' }}</span>
        <!-- <el-tag size="small" type="primary" effect="light" round>Lv.{{ level || 6 }}</el-tag> -->
      </div>
      <p class="bio">{{ user.motto || '这个人很懒，什么都没写。' }}</p>
    </div>

    <div class="social-stats">
      <div class="s-item clickable" @click="goToRelation('following')">
        <div class="num">{{ formatNumber(user.followingCount || 0) }}</div>
        <div class="label">关注</div>
      </div>
      <el-divider direction="vertical" />
      
      <div class="s-item clickable" @click="goToRelation('followers')">
        <div class="num">{{ formatNumber(localFollowerCount || 0) }}</div>
        <div class="label">粉丝</div>
      </div>
      <el-divider direction="vertical" />
      
      <div class="s-item">
        <div class="num">{{ formatNumber(user.likeCount || 0) }}</div>
        <div class="label">获赞</div>
      </div>
    </div>

    <el-divider border-style="dashed" />

    <!-- <div class="section-box">
      <div class="section-title">成就徽章</div>
      <div class="badges-grid">
        <div class="badge-item" title="AC 100 题">🏆</div>
        <div class="badge-item" title="打卡达人">🔥</div>
        <div class="badge-item" title="C++ 专家">⚡️</div>
        <div class="badge-item" title="比赛前十">🌟</div>
      </div>
    </div> -->
    <!-- <el-divider border-style="dashed" /> -->

    <!-- <div class="section-box">
      <div class="section-title">能力分析</div>
      <div class="ability-list">
        <div class="ability-item" v-for="item in abilityData" :key="item.name">
          <div class="ab-head">
            <span class="ab-name">{{ item.name }}</span>
            <span class="ab-score">{{ item.score }}</span>
          </div>
          <el-progress 
            :percentage="item.value" 
            :color="item.color" 
            :stroke-width="8" 
            :show-text="false"
          />
        </div>
      </div>
    </div> -->

    <div class="edit-action">
      <el-button 
        v-if="isSelf" 
        style="width: 100%" 
        round 
        @click="$router.push({ name: 'UserSetting' })"
      >
        编辑资料
      </el-button>
      
      <el-button 
        v-else 
        style="width: 100%" 
        round
        :type="localIsFollowed ? 'info' : 'primary'"
        :plain="localIsFollowed"
        :loading="followLoading"
        @click="handleFollow"
      >
        {{ localIsFollowed ? '已关注' : '+ 关注' }}
      </el-button>
    </div>
  </el-card>
</template>

<script setup>
// 用户信息卡：头像、昵称、等级与关注按钮。
// 关注状态与粉丝数在本地维护一份：props 是父组件的只读数据源，
// 关注后若等父组件重新请求，用户会看到"点了没反应"。
import { computed, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { toggleFollow } from '@/api/user'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/userStore'
import { getDefaultAvatar } from '@/utils/dicebear'

const userStore = useUserStore()

const props = defineProps({
  user: {
    type: Object,
    default: () => ({})
  },
  level: {
    type: Number,
    default: 0
  }
})

const router = useRouter()
const route = useRoute()
const followLoading = ref(false)
const localIsFollowed = ref(false) // 本地维护关注状态，避免刷新页面才能看到变化
const localFollowerCount = ref(0) // 本地粉丝数（乐观更新用，不直接改 props）

// 1. 判断是否是自己
const isSelf = computed(() => {
  const myId = userStore.userId
  if (!myId) return false
  return String(myId) === String(props.user.id)
})

const defaultAvatar = getDefaultAvatar()

// props 变化时同步回本地：切换到另一位用户的资料页时，
// 不加这个 watch 会把上一个用户的关注状态带过来。
watch(() => props.user.isFollowed, (newVal) => {
  localIsFollowed.value = !!newVal
}, { immediate: true })

watch(() => props.user.followerCount, (newVal) => {
  localFollowerCount.value = newVal || 0
}, { immediate: true })

// 跳转到关注粉丝列表
const goToRelation = (type) => {
  router.push({
    name: 'UserRelation',
    params: { 
      id: route.params.id // 或者 props.user.id
    },
    query: { 
      type: type // 'following' 或 'followers'
    }
  })
}

// 3. 处理关注/取关
const handleFollow = async () => {
  if (!userStore.userId) {
    ElMessage.warning('请先登录')
    return
  }
  
  followLoading.value = true
  try {
    const res = await toggleFollow({ followedId: props.user.id })
    if (res.code === 200) {
      // 乐观更新：请求成功后本地翻转状态并把粉丝数 ±1，不重新拉整个用户资料。
// 若这里改成重查，请求失败时数字会跳回去，体验反而更差。
      localIsFollowed.value = !localIsFollowed.value
      if (localIsFollowed.value) {
        localFollowerCount.value = (localFollowerCount.value || 0) + 1
        ElMessage.success('关注成功')
      } else {
        localFollowerCount.value = (localFollowerCount.value || 0) - 1
        ElMessage.success('已取消关注')
      }
    } else {
      ElMessage.error('操作失败')
    }
  } catch (e) {
    console.error(e)
  } finally {
    followLoading.value = false
  }
}

// 粉丝数过大时用 k 缩写：上万位的数字会把卡片撑破。
const formatNumber = (num) => {
  if (!num) return 0
  if (num >= 1000) {
    return (num / 1000).toFixed(1) + 'k'
  }
  return num
}

</script>

<style scoped>
/* 允许溢出：让头像突破卡片边界显示 */
.info-card-sticky {
  border-radius: 12px;
  border: none;
  overflow: visible !important; 
}
.info-card-sticky :deep(.el-card__body) {
  overflow: visible !important;
}

.avatar-wrapper {
  position: relative;
  margin-top: -70px; /* 上移头像 */
  margin-bottom: 15px;
  text-align: center;
  z-index: 50; 
}

.user-avatar {
  border: 4px solid var(--bg-surface);
  box-shadow: var(--shadow-md);
  background: var(--bg-surface);
  position: relative; 
}

/* 文字部分 */
.user-identity { text-align: center; margin-bottom: 20px; }
.nickname { margin: 0 0 5px 0; font-size: 22px; font-weight: bold; color: var(--text-primary); }
.username-row { display: flex; align-items: center; justify-content: center; gap: 8px; margin-bottom: 10px; }
.username { color: var(--text-secondary); font-size: 14px; }
.bio { font-size: 13px; color: var(--text-regular); padding: 0 20px; word-break: break-all; }

/* 社交数据 */
.social-stats { display: flex; justify-content: center; margin-bottom: 20px; }
.s-item { text-align: center; padding: 0 10px; transition: opacity 0.2s; }

/* 鼠标悬停变成小手 */
.clickable { cursor: pointer; }
.clickable:hover { opacity: 0.7; }
.clickable:hover .num { color: var(--el-color-primary); }

.s-item .num { font-weight: bold; font-size: 16px; color: var(--text-primary); transition: color 0.2s; }
.s-item .label { font-size: 12px; color: var(--text-secondary); }

/* 分隔线与区块 */
.section-box { margin: 20px 0; }
.section-title { font-size: 14px; font-weight: bold; color: var(--text-primary); margin-bottom: 12px; }
.badges-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.badge-item { aspect-ratio: 1; background: var(--bg-surface-hover); border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 20px; cursor: help; }

/* 能力条 */
.ability-list { display: flex; flex-direction: column; gap: 12px; }
.ab-head { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 4px; }
.ab-name { color: var(--text-regular); }
.ab-score { font-weight: bold; color: var(--text-primary); }
</style>
