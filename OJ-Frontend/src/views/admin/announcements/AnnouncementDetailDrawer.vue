<template>
  <el-drawer
    :model-value="modelValue"
    title="公告详情"
    :size="drawerSize"
    direction="rtl"
    destroy-on-close
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <template v-if="row">
      <div class="drawer-head">
        <div class="dh-line">
          <span class="dh-title">{{ row.title }}</span>
          <el-tag v-if="row.isTop === 1" type="warning" effect="light" size="small">置顶</el-tag>
          <el-tag v-else type="info" effect="plain" size="small">普通</el-tag>
        </div>
        <div class="dh-meta">
          发布于 {{ formatAnnouncementTime(row.createTime) }} · ID {{ row.id }} · 浏览 {{ row.views ?? 0 }}
        </div>
      </div>

      <div class="d-section">
        <div class="d-section-title">正文预览 <span class="d-hint">与前台公告详情渲染一致</span></div>
        <div class="markdown-wrap">
          <PostContent v-if="row.content" :content="row.content" />
          <span v-else class="d-empty">暂无正文</span>
        </div>
      </div>

      <div class="d-section">
        <div class="d-section-title">原始 Markdown</div>
        <div class="raw-box">{{ row.content || '暂无正文' }}</div>
      </div>
    </template>

    <template #footer>
      <div class="drawer-footer">
        <el-button type="danger" plain :disabled="!row" @click="emit('delete', row)">删除公告</el-button>
        <el-button :disabled="!row" @click="emit('preview', row)">前台预览</el-button>
        <el-button type="primary" :disabled="!row" @click="emit('edit', row)">编辑公告</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
// 详情抽屉：正文复用前台 PostContent（markdown-it html:false + 公式 + 代码高亮），
// 保证管理员看到的效果与发布后一致；另附原始 Markdown 便于核对格式。
import { computed } from 'vue'
import PostContent from '@/views/post/components/PostContent.vue'
import { formatAnnouncementTime } from './announcementFormat'

const props = defineProps({
  modelValue: Boolean,
  row: { type: Object, default: null },
  full: Boolean
})

const emit = defineEmits(['update:modelValue', 'delete', 'preview', 'edit'])
const drawerSize = computed(() => (props.full ? '100%' : '520px'))
</script>

<style scoped>
.drawer-head { padding-bottom: 12px; border-bottom: 1px solid var(--border-light); }
.dh-line { display: flex; align-items: center; gap: 8px; min-width: 0; }
.dh-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dh-meta { font-size: 12px; color: var(--text-regular); margin-top: 8px; line-height: 1.6; }
.d-section { margin-top: 18px; }
.d-section-title { font-size: 14px; font-weight: 600; color: var(--text-primary); margin-bottom: 10px; }
.d-hint { font-size: 12px; font-weight: 400; color: var(--text-regular); margin-left: 6px; }
.markdown-wrap {
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  padding: 14px;
  max-height: 340px;
  overflow-y: auto;
  background: var(--bg-surface);
}
.d-empty { font-size: 13px; color: var(--text-regular); }
.raw-box {
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  padding: 12px;
  background: var(--bg-app);
  color: var(--text-regular);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 220px;
  overflow-y: auto;
}
.drawer-footer { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
</style>
