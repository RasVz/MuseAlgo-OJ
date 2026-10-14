<template>
  <div class="comment-item">
    <div class="root-avatar">
      <el-avatar 
        :size="40" 
        :src="comment.avatar || defaultAvatar" 
        class="avatar-hover" 
        @click="goToUser(comment.userId)" 
      />
    </div>

    <div class="content-container">
      <div class="user-info">
        <span class="nickname" @click="goToUser(comment.userId)">
          {{ comment.nickname || '匿名用户' }}
        </span>
        <span class="time">{{ formatTime(comment.createTime) }}</span>
      </div>

      <div class="main-content">
        {{ comment.content }}
      </div>

      <div class="action-box">
        <span class="btn-action" @click="handleReply(comment)">
          <el-icon><ChatLineSquare /></el-icon> 回复
        </span>
        
        <span 
          class="btn-action" 
          :class="{ 'is-liked': comment.isLiked }" 
          @click="handleLike(comment)"
        >
          <svg viewBox="0 0 24 24" width="14" height="14" :fill="comment.isLiked ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
          {{ comment.isLiked ? '已赞' : '点赞' }} 
          <span v-if="comment.likeCount > 0">({{ comment.likeCount }})</span>
        </span>
      </div>

      <div class="sub-comments-box" v-if="replies && replies.length > 0">
        
        <div 
          v-for="sub in visibleReplies" 
          :key="sub.id" 
          class="sub-item"
        >
          <div class="sub-content-row">
            <el-avatar 
              :size="24" 
              :src="sub.avatar || defaultAvatar" 
              class="sub-avatar avatar-hover" 
              @click="goToUser(sub.userId)" 
            />
            
            <div class="sub-text-flow">
              <span class="sub-user" @click="goToUser(sub.userId)">
                {{ sub.nickname || '匿名' }}
              </span>
              
              <template v-if="sub.replyUserId && String(sub.replyUserId) !== String(comment.userId)">
                 <span class="reply-text">回复</span>
                 <span class="sub-target" @click="goToUser(sub.replyUserId)">
                   @{{ sub.replyNickname || '未知用户' }}
                 </span>
              </template>
              
              <span class="colon">：</span>
              <span class="sub-body">{{ sub.content }}</span>
            </div>
          </div>

          <div class="sub-actions">
            <span class="sub-time">{{ formatTime(sub.createTime) }}</span>
            
            <span class="sub-reply-btn" @click="handleReply(sub)">
              <el-icon class="icon-small"><ChatLineSquare /></el-icon> 回复
            </span>
            
            <span 
              class="sub-like-btn" 
              :class="{ 'is-liked': sub.isLiked }" 
              @click="handleLike(sub)"
            >
              <svg viewBox="0 0 24 24" width="12" height="12" :fill="sub.isLiked ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
              <span>{{ sub.isLiked ? '已赞' : '点赞' }}</span>
              <span v-if="sub.likeCount > 0">{{ sub.likeCount }}</span>
            </span>
          </div>
        </div>

        <div v-if="replies.length > defaultLimit" class="expand-control">
          <span v-if="!isExpanded" @click="isExpanded = true" class="expand-btn">
            共 {{ replies.length }} 条回复，点击查看 <el-icon><CaretBottom /></el-icon>
          </span>
          <span v-else @click="isExpanded = false" class="expand-btn">
            收起 <el-icon><CaretTop /></el-icon>
          </span>
        </div>

      </div>
    </div>
  </div>
</template>

<script setup>
// 单条评论（含其楼中楼回复）：支持点赞、回复与折叠长回复列表。
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ChatLineSquare, CaretBottom, CaretTop } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { toggleCommentLike } from '@/api/post'
import { ElMessage } from 'element-plus'

const props = defineProps({
  comment: { type: Object, required: true }
})

const emit = defineEmits(['reply'])
const router = useRouter()

const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const isExpanded = ref(false)
const defaultLimit = 2

// 后端在不同版本里对子回复用过 children 与 replies 两种字段名，两个都兜住。
const replies = computed(() => props.comment.children || props.comment.replies || [])

// 回复默认只显示 2 条：热门帖的楼中楼可能有上百条，全展开会把主评论挤到看不见。
const visibleReplies = computed(() => {
  return isExpanded.value ? replies.value : replies.value.slice(0, defaultLimit)
})

const formatTime = (time) => {
  if (!time) return ''
  return dayjs(time).format('MM-DD HH:mm')
}

const goToUser = (userId) => {
  if (userId) {
    router.push({ name: 'UserProfile', params: { id: userId } })
  }
}

// 回复永远挂在根评论（rootId）下：楼中楼只做一层，
// 再嵌套会让渲染与通知链路复杂化，用户也没实际需要。
const handleReply = (targetComment) => {
  if (!targetComment.userId) {
    ElMessage.warning('数据异常：无法获取用户ID')
    return
  }
  emit('reply', {
    rootId: props.comment.id,
    commentId: targetComment.id,
    toUser: {
      id: targetComment.userId,
      name: targetComment.nickname
    }
  })
}

// 点赞是乐观更新：先翻转 UI 让点击有即时反馈，请求失败再回滚。
// 评论区点赞是高频操作，等一次往返会让界面显得"卡"。
const handleLike = async (item) => {
  try {
    const oldStatus = item.isLiked
    item.isLiked = !oldStatus
    if (item.isLiked) {
      item.likeCount = (item.likeCount || 0) + 1
    } else {
      item.likeCount = (item.likeCount || 0) - 1
    }

    const res = await toggleCommentLike(item.id, item.isLiked)
    if (res.code !== 200) {
      // 回滚：恢复点赞态与计数。计数用反向加减而不是记 oldCount，
      // 是因为期间可能有其他人点赞，加回旧值会把别人的那份抹掉。
      item.isLiked = oldStatus
      item.likeCount = oldStatus ? item.likeCount + 1 : item.likeCount - 1
      ElMessage.error(res.msg || '操作失败')
    }
  } catch (e) {
    console.error(e)
  }
}
</script>

<style scoped>
.comment-item {
  display: flex; gap: 14px; margin-bottom: 24px;
  padding: 20px; background: var(--bg-surface);
  border: 1px solid var(--border-light); border-radius: var(--radius-md);
  transition: border-color 0.15s;
}
.comment-item:hover { border-color: var(--border-hover); }
.root-avatar { flex-shrink: 0; }
.content-container { flex: 1; min-width: 0; }

.user-info { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.nickname { font-weight: 600; font-size: 14px; color: var(--text-primary); cursor: pointer; }
.nickname:hover { color: var(--accent-primary); }
.time { font-size: 12px; color: var(--text-secondary); }

.main-content { font-size: 15px; color: var(--text-primary); line-height: 1.7; margin-bottom: 10px; word-break: break-word; }

.action-box { display: flex; gap: 20px; font-size: 13px; color: var(--text-secondary); margin-bottom: 12px; }
.btn-action { cursor: pointer; display: flex; align-items: center; gap: 5px; }
.btn-action:hover { color: var(--accent-primary); }
.btn-action.is-liked { color: var(--accent-danger); }

.sub-comments-box {
  background: var(--bg-app); border-radius: var(--radius-sm); padding: 16px;
  display: flex; flex-direction: column; gap: 14px;
  border: 1px solid var(--border-light); margin-top: 4px;
}
.sub-item { font-size: 14px; }
.sub-content-row { display: flex; align-items: flex-start; gap: 8px; line-height: 22px; }
.sub-avatar { margin-top: 0; }
.sub-text-flow { flex: 1; word-break: break-all; }
.sub-user { color: var(--text-primary); font-weight: 500; cursor: pointer; }
.sub-user:hover { color: var(--accent-primary); }

.reply-text { margin: 0 4px; color: var(--text-secondary); font-size: 13px; }
.sub-target { color: var(--accent-primary); cursor: pointer; font-weight: 500; }
.sub-target:hover { text-decoration: underline; }

.sub-body { color: var(--text-primary); }

.sub-actions { margin-left: 34px; margin-top: 4px; font-size: 12px; color: var(--text-secondary); display: flex; gap: 14px; }

.sub-reply-btn, .sub-like-btn { cursor: pointer; display: flex; align-items: center; gap: 3px; }
.sub-reply-btn:hover { color: var(--accent-primary); }
.sub-like-btn:hover { color: var(--accent-danger); }
.sub-like-btn.is-liked { color: var(--accent-danger); }

.icon-small { font-size: 12px; }

.expand-control { font-size: 13px; color: var(--text-secondary); margin-top: 5px; }
.expand-btn { cursor: pointer; display: inline-flex; align-items: center; gap: 2px; }
.expand-btn:hover { color: var(--accent-primary); }

.avatar-hover { cursor: pointer; }
</style>
