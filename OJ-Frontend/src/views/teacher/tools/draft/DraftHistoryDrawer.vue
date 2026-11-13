<template>
  <el-drawer
    :model-value="modelValue"
    title="历史草稿"
    size="420px"
    :append-to-body="false"
    class="draft-history"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="drawer-head">
        <span class="drawer-title">历史草稿</span>
        <span class="drawer-count">共 {{ total }} 条</span>
      </div>
    </template>

    <div v-loading="loading" class="drawer-body">
      <el-empty v-if="!items.length && !loading" description="还没有生成记录" :image-size="72" />

      <article v-for="item in items" :key="item.id" class="history-item">
        <div class="item-head">
          <el-tag size="small" :type="statusOf(item).type" effect="plain">
            {{ statusOf(item).label }}
          </el-tag>
          <span v-if="item.variantNo > 1" class="item-variant">方案 {{ variantLetter(item) }}</span>
          <span class="item-time">{{ formatTime(item.createTime) }}</span>
        </div>

        <button type="button" class="item-title" @click="$emit('open', item.id)">
          {{ item.title || item.briefSummary || `草稿 #${item.id}` }}
        </button>

        <div class="item-meta">
          <span v-if="item.errorCount" class="meta-error">阻断 {{ item.errorCount }}</span>
          <span v-if="item.warnCount" class="meta-warn">提醒 {{ item.warnCount }}</span>
          <span v-if="item.elapsedMs">{{ formatElapsed(item.elapsedMs) }}</span>
          <span v-if="item.problemId" class="meta-saved">已落库 #{{ item.problemId }}</span>
        </div>

        <div class="item-actions">
          <el-button size="small" @click="$emit('open', item.id)">打开</el-button>
          <el-button size="small" :disabled="!item.briefSummary && !item.brief" @click="$emit('reuse-brief', item)">
            复用简报
          </el-button>
          <el-button size="small" text type="danger" @click="$emit('remove', item.id)">删除</el-button>
        </div>
      </article>
    </div>

    <template #footer>
      <div class="drawer-foot">
        <el-button size="small" :loading="loading" @click="$emit('refresh')">刷新</el-button>
        <el-pagination
          small
          background
          layout="prev, pager, next"
          :current-page="page"
          :page-size="pageSize"
          :total="total"
          @current-change="$emit('page-change', $event)"
        />
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
// 历史草稿抽屉：只呈现列表与分页，开/删/复用全部上抛给页面壳。
import { statusMeta, formatElapsed } from './draftModel'

defineProps({
  modelValue: { type: Boolean, default: false },
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 }
})

defineEmits(['update:modelValue', 'open', 'remove', 'reuse-brief', 'page-change', 'refresh'])

const statusOf = (item) => statusMeta(item?.status, item?.phase)

const variantLetter = (item) => String.fromCharCode(64 + (item.variantNo || 1))

// 后端返回 ISO 字符串；这里只做展示裁剪，不做时区转换
const formatTime = (value) => {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.slice(0, 16)
}
</script>

<style scoped>
.drawer-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.drawer-title { font-size: 15px; font-weight: 600; color: var(--text-primary); }
.drawer-count { font-size: 12px; color: var(--text-secondary); }

.drawer-body { min-height: 200px; }

.history-item {
  padding: 10px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}
.item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.item-variant {
  font-size: 11px;
  color: var(--accent-primary);
  background: var(--accent-primary-light);
  border-radius: 999px;
  padding: 0 6px;
}
.item-time { margin-left: auto; font-size: 11px; color: var(--text-secondary); }

.item-title {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
  font-size: 13.5px;
  line-height: 1.5;
  color: var(--text-primary);
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-title:hover { color: var(--accent-primary); text-decoration: underline; }
.item-title:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 2px; }

.item-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--text-secondary);
  flex-wrap: wrap;
}
.meta-error { color: var(--accent-danger); }
.meta-warn { color: var(--accent-warning); }
.meta-saved { color: var(--accent-success); }

.item-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

:deep(.el-drawer__body) { padding: 12px 16px; }
:deep(.el-drawer__header) { margin-bottom: 0; padding: 12px 16px; border-bottom: 1px solid var(--border-light); }
:deep(.el-drawer__footer) { padding: 10px 16px; border-top: 1px solid var(--border-light); }
</style>
