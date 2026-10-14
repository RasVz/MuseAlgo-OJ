<template>
  <div class="author-footer">
    <div class="af-left">
      <el-avatar :size="56" :src="author.avatar">{{ (author.name || 'U').charAt(0) }}</el-avatar>
    </div>
    <div class="af-center">
      <div class="af-name-row">
        <span class="af-name" @click="goUser(author.id)">{{ author.name }}</span>
        <el-tag size="small" type="info" effect="plain">作者</el-tag>
      </div>
      <div class="af-bio">{{ author.motto || '这个人很神秘，什么都没写。' }}</div>
    </div>
    <div class="af-right" v-if="!isSelf">
      <el-button :type="isFollowed ? 'default' : 'primary'" size="small" round @click="$emit('toggle-follow')">
        {{ isFollowed ? '已关注' : '+ 关注' }}
      </el-button>
    </div>
  </div>
</template>

<script setup>
// 文章作者卡：头像、昵称、签名与关注按钮。isSelf 为 true 时隐藏关注按钮
// （关注自己是无效操作），关注请求由父组件发起。
import { useRouter } from 'vue-router'

defineProps({
  author: { type: Object, required: true },
  isSelf: { type: Boolean, default: false },
  isFollowed: { type: Boolean, default: false }
})

defineEmits(['toggle-follow'])

const router = useRouter()
const goUser = (id) => router.push({ name: 'UserProfile', params: { id } })
</script>

<style scoped>
.author-footer {
  background: var(--bg-app); border-radius: var(--radius-md); padding: 24px 28px;
  display: flex; align-items: center; gap: 18px;
  margin-bottom: 36px; border: 1px solid var(--border-light);
}

.af-left { flex-shrink: 0; }
.af-center { flex: 1; min-width: 0; }

.af-name-row { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.af-name { font-weight: 600; font-size: 15px; color: var(--text-primary); cursor: pointer; }
.af-name:hover { color: var(--accent-primary); }
.af-bio { color: var(--text-secondary); font-size: 13px; }
.af-right { margin-left: auto; flex-shrink: 0; }
</style>
