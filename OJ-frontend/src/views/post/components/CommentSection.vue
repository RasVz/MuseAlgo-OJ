<template>
  <div class="comment-section">
    <div class="sec-header">
      <span class="title">评论</span>
      <span class="count">{{ totalCount }}</span>
    </div>

    <div class="input-area" id="comment-input-area">
      <el-avatar :size="40" :src="userAvatar || defaultAvatar" />
      <div class="input-box">
        <el-input 
          v-model="content" 
          type="textarea" 
          :rows="3" 
          :placeholder="placeholder"
          resize="none"
        />
        <div class="input-footer">
          <span v-if="replyTarget" class="cancel-reply" @click="cancelReply">
            取消回复 @{{ replyTarget.toUser.name }}
          </span>
          <el-button type="primary" size="small" :loading="submitting" @click="submit">
            发表评论
          </el-button>
        </div>
      </div>
    </div>

    <div class="list-area">
      <CommentItem 
        v-for="item in comments" 
        :key="item.id" 
        :comment="item" 
        @reply="onReceiveReply"
      />
    </div>
  </div>
</template>

<script setup>
// 评论区容器：发表评论、接收"回复某条"的意图，并把评论树交给 CommentItem 渲染。
// 提交成功后 emit('refresh') 让父组件重拉整棵树——
// 评论树的组装逻辑在父组件里，这里不重复实现，代价只是一次请求。
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import CommentItem from './CommentItem.vue'
import { useRoute } from 'vue-router'
import { addComment } from '@/api/post'
import { useUserStore } from '@/stores/userStore'

const props = defineProps(['comments'])
const userStore = useUserStore()
const emit = defineEmits(['refresh'])

const route = useRoute()
const content = ref('')
const submitting = ref(false)
const replyTarget = ref(null)

// 默认头像
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

// 总数要递归算楼中楼：只数根评论会让"共 N 条"与实际不符。
const totalCount = computed(() => {
  let count = props.comments ? props.comments.length : 0
  if (props.comments) {
    props.comments.forEach(c => {
      if (c.children) count += c.children.length
    })
  }
  return count
})

const placeholder = computed(() => {
  if (replyTarget.value) {
    return `回复 @${replyTarget.value.toUser.name}...`
  }
  return '友善评论，理性交流...'
})

const userAvatar = userStore.userInfo.avatar

// 点"回复"后自动把输入框滚到视野中央：
// 用户点的是页面下方的某条回复，输入框可能在屏幕外，不提示会以为没反应。
const onReceiveReply = (targetInfo) => {
  replyTarget.value = targetInfo
  // 滚动聚焦
  const inputEl = document.getElementById('comment-input-area')
  if (inputEl) inputEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

const cancelReply = () => {
  replyTarget.value = null
  content.value = ''
}

const submit = async () => {
  if (!userStore.userId) {
    ElMessage.warning('请先登录')
    return
  }
  if (!content.value.trim()) {
    ElMessage.warning('写点什么吧')
    return
  }
  
  // entityType=1 表示评论目标是帖子（评论体系也服务于班级动态等场景）。
  const dto = {
    entityId: route.params.id,
    content: content.value,
    entityType: 1
  }

  // parentId 指向根评论（楼中楼只做一层），replyCommentId 记录被回复的那条
  if (replyTarget.value) {
    dto.parentId = replyTarget.value.rootId
    dto.replyUserId = replyTarget.value.toUser.id
    dto.replyCommentId = replyTarget.value.commentId
  } else {
    dto.parentId = 0
  }

  submitting.value = true
  try {
    const res = await addComment(dto)
    if (res.code === 200) {
      ElMessage.success('评论成功')
      content.value = ''
      replyTarget.value = null
      emit('refresh') // 刷新列表
    } else {
      ElMessage.error(res.msg || '评论失败')
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('网络错误')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.comment-section {  }
.sec-header { font-size: 18px; font-weight: 700; margin-bottom: 24px; display: flex; gap: 8px; align-items: center; color: var(--text-primary); }
.count { font-size: 14px; color: var(--text-secondary); font-weight: 400; }

.input-area {
  display: flex; gap: 14px; margin-bottom: 36px;
  background: var(--bg-app); border-radius: var(--radius-md);
  padding: 18px; border: 1px solid var(--border-light);
}
.input-box { flex: 1; display: flex; flex-direction: column; }
.input-box :deep(.el-textarea__inner) {
  border-radius: var(--radius-sm); border-color: var(--border-light);
  background: var(--bg-surface); resize: none; font-size: 14px;
}
.input-box :deep(.el-textarea__inner):focus { border-color: var(--accent-primary); }
.input-footer { margin-top: 10px; display: flex; justify-content: flex-end; align-items: center; gap: 14px; }
.cancel-reply { font-size: 13px; color: var(--text-secondary); cursor: pointer; }
.cancel-reply:hover { color: var(--accent-danger); }
</style>
