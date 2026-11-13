<template>
  <section class="user-info-card">
    <el-avatar :size="88" :src="user.avatar || defaultAvatar" class="user-avatar" />
    <h1 class="nickname">{{ user.nickname || user.username || '未命名用户' }}</h1>
    <p class="username">@{{ user.username }}</p>
    <p v-if="user.motto" class="bio">{{ user.motto }}</p>
    <p v-else-if="isSelf" class="bio empty-bio">写一句话，介绍现在的你。</p>
    <div class="social-stats">
      <button type="button" @click="goToRelation('following')">
        <strong>{{ formatNumber(user.followingCount || 0) }}</strong
        ><span>关注</span>
      </button>
      <button type="button" @click="goToRelation('followers')">
        <strong>{{ formatNumber(localFollowerCount || 0) }}</strong
        ><span>粉丝</span>
      </button>
      <div>
        <strong>{{ formatNumber(user.likeCount || 0) }}</strong
        ><span>获赞</span>
      </div>
    </div>
    <div v-if="user.school || user.major" class="user-details">
      <span v-if="user.school">{{ user.school }}</span
      ><span v-if="user.major">{{ user.major }}</span>
    </div>
    <el-button v-if="isSelf" class="profile-action" @click="$router.push({ name: 'UserSetting' })"
      >编辑资料</el-button
    >
    <el-button
      v-else
      class="profile-action"
      :type="localIsFollowed ? 'default' : 'primary'"
      :loading="followLoading"
      @click="handleFollow"
      >{{ localIsFollowed ? '已关注' : '+ 关注' }}</el-button
    >
  </section>
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
    default: () => ({}),
  },
  level: {
    type: Number,
    default: 0,
  },
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
watch(
  () => props.user.isFollowed,
  (newVal) => {
    localIsFollowed.value = !!newVal
  },
  { immediate: true },
)

watch(
  () => props.user.followerCount,
  (newVal) => {
    localFollowerCount.value = newVal || 0
  },
  { immediate: true },
)

// 跳转到关注粉丝列表
const goToRelation = (type) => {
  router.push({
    name: 'UserRelation',
    params: {
      id: route.params.id, // 或者 props.user.id
    },
    query: {
      type: type, // 'following' 或 'followers'
    },
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
.user-info-card {
  padding: 24px 20px 22px;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 16px;
  text-align: center;
  position: relative;
}
.user-avatar {
  border: 4px solid var(--bg-surface);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  background: var(--bg-app);
}
.nickname {
  margin: 14px 0 4px;
  font-size: 22px;
  font-weight: 650;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
.username {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.bio {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-regular);
  margin: 16px 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.empty-bio {
  color: var(--text-secondary);
}
.social-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
  margin: 22px 0;
}
.social-stats > * {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 46px;
  background: none;
  border: 0;
  padding: 0;
  font: inherit;
}
.social-stats button {
  cursor: pointer;
}
.social-stats button:hover strong {
  color: var(--el-color-primary);
}
.social-stats strong {
  color: var(--text-primary);
  font-size: 18px;
  font-weight: 600;
}
.social-stats span {
  color: var(--text-secondary);
  font-size: 12px;
}
.user-details {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
  border-top: 1px solid var(--border-light);
  padding-top: 16px;
  margin-bottom: 18px;
  overflow-wrap: anywhere;
}
.profile-action {
  width: 100%;
  border-radius: 8px;
  min-height: 36px;
}
@media (max-width: 680px) {
  .user-info-card {
    padding: 18px 20px;
  }
  .user-avatar {
    width: 76px !important;
    height: 76px !important;
  }
  .social-stats {
    max-width: 260px;
    margin: 16px auto;
  }
  .bio {
    margin: 12px 0;
  }
  .profile-action {
    max-width: 300px;
  }
}
</style>
