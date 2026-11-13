<template>
  <div class="ann-table-wrap" v-loading="loading">
    <el-table
      v-if="!mobile"
      :data="rows"
      row-key="id"
      border
      stripe
      class="ann-table"
    >
      <el-table-column label="公告" min-width="260">
        <template #default="{ row }">
          <div class="cell-main">
            <div class="cell-title-line">
              <button
                type="button"
                class="row-title"
                :aria-label="`查看「${row.title}」详情`"
                @click="emit('detail', row)"
              >{{ row.title }}</button>
              <el-tag v-if="row.isTop === 1" type="warning" effect="light" size="small">置顶</el-tag>
            </div>
            <div class="row-excerpt">{{ announcementExcerpt(row.content) || '暂无内容' }}</div>
            <div v-if="compact" class="row-time-inline">{{ formatAnnouncementTime(row.createTime) }}</div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="置顶" :width="compact ? 76 : 96" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.isTop === 1"
            :disabled="topSavingId === row.id"
            :aria-label="`切换「${row.title}」的置顶状态`"
            @update:model-value="(v) => emit('toggle-top', row, v ? 1 : 0)"
          />
        </template>
      </el-table-column>

      <el-table-column v-if="!compact" label="发布时间" width="160">
        <template #default="{ row }">
          <span class="cell-time" :title="String(row.createTime || '')">{{ formatAnnouncementTime(row.createTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" :width="compact ? 96 : 140" align="center">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            :aria-label="`查看「${row.title}」详情`"
            @click="emit('detail', row)"
          >
            <el-icon v-if="compact"><View /></el-icon>
            <span v-else>详情</span>
          </el-button>
          <el-button
            link
            :aria-label="`编辑「${row.title}」`"
            @click="emit('edit', row)"
          >
            <el-icon v-if="compact"><EditPen /></el-icon>
            <span v-else>编辑</span>
          </el-button>
        </template>
      </el-table-column>

      <template #empty>
        <div class="table-empty">{{ emptyText }}</div>
      </template>
    </el-table>

    <div v-else class="card-list">
      <div v-for="row in rows" :key="row.id" class="ann-card">
        <div class="card-title-line">
          <button
            type="button"
            class="row-title"
            :aria-label="`查看「${row.title}」详情`"
            @click="emit('detail', row)"
          >{{ row.title }}</button>
          <el-tag v-if="row.isTop === 1" type="warning" effect="light" size="small">置顶</el-tag>
        </div>
        <div class="row-excerpt">{{ announcementExcerpt(row.content) || '暂无内容' }}</div>
        <div class="card-meta">{{ formatAnnouncementTime(row.createTime) }} · ID {{ row.id }}</div>
        <div class="card-actions">
          <el-switch
            :model-value="row.isTop === 1"
            :disabled="topSavingId === row.id"
            :aria-label="`切换「${row.title}」的置顶状态`"
            @update:model-value="(v) => emit('toggle-top', row, v ? 1 : 0)"
          />
          <span class="card-switch-label">置顶</span>
          <el-button size="small" :aria-label="`查看「${row.title}」详情`" @click="emit('detail', row)">详情</el-button>
          <el-button size="small" :aria-label="`编辑「${row.title}」`" @click="emit('edit', row)">编辑</el-button>
        </div>
      </div>
      <div v-if="!loading && rows.length === 0" class="table-empty">{{ emptyText }}</div>
    </div>
  </div>
</template>

<script setup>
// 公告数据表：桌面用表格，窄屏用卡片列表。
// 置顶开关是受控组件，父级只在接口成功后更新数据，失败按服务端列表重拉回滚。
import { View, EditPen } from '@element-plus/icons-vue'
import { formatAnnouncementTime, announcementExcerpt } from './announcementFormat'

defineProps({
  rows: { type: Array, default: () => [] },
  loading: Boolean,
  compact: Boolean,
  mobile: Boolean,
  topSavingId: { type: Number, default: null },
  emptyText: { type: String, default: '暂无公告' }
})

const emit = defineEmits(['detail', 'edit', 'toggle-top'])
</script>

<style scoped>
.ann-table-wrap { width: 100%; }
.ann-table { width: 100%; }
.cell-main { min-width: 0; }
.cell-title-line { display: flex; align-items: center; gap: 8px; min-width: 0; }
.row-title {
  background: none;
  border: 0;
  padding: 0;
  margin: 0;
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-title:hover { color: var(--accent-primary); text-decoration: underline; }
.row-title:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 2px; border-radius: 2px; }
.row-excerpt {
  color: var(--text-regular);
  font-size: 12px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-time-inline { color: var(--text-regular); font-size: 12px; margin-top: 4px; }
.cell-time { color: var(--text-regular); font-size: 13px; }
.table-empty { padding: 28px 0; color: var(--text-regular); font-size: 13px; }

.card-list { display: flex; flex-direction: column; gap: 10px; }
.ann-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  padding: 12px;
  min-width: 0;
}
.card-title-line { display: flex; align-items: center; gap: 8px; min-width: 0; }
.card-meta { color: var(--text-regular); font-size: 12px; margin-top: 6px; }
.card-actions { display: flex; align-items: center; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.card-switch-label { color: var(--text-regular); font-size: 12px; margin-right: auto; }

@media (max-width: 767px) {
  .row-excerpt { white-space: normal; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
}
</style>
