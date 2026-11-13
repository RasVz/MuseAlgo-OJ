<template>
  <el-drawer
    :model-value="modelValue"
    :title="row ? row.title : '题单详情'"
    :size="drawerSize"
    direction="rtl"
    destroy-on-close
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <template v-if="row">
      <div class="drawer-head">
        <div class="dh-line">
          <span class="dh-title">{{ row.title }}</span>
          <el-tag size="small" effect="light" :type="row.privacy === 1 ? 'success' : 'info'">
            {{ row.privacy === 1 ? '公开' : '私密' }}
          </el-tag>
        </div>
        <div class="dh-meta">
          创建于 {{ fmtDate(row.createTime) }} · 更新于 {{ fmtDate(row.updateTime) }} · ID {{ row.id }}
        </div>
      </div>

      <div class="d-stats">
        <div class="d-stat">
          <div class="d-label">题目数量</div>
          <div class="d-value">{{ row.problemCount ?? 0 }}</div>
        </div>
        <div class="d-stat">
          <div class="d-label">关联课件</div>
          <div class="d-value">{{ row.coursewareCount ?? 0 }}</div>
        </div>
        <div class="d-stat">
          <div class="d-label">累计收藏</div>
          <div class="d-value">{{ row.favoriteCount ?? 0 }}</div>
        </div>
      </div>

      <div class="d-section">
        <div class="d-section-title">简介</div>
        <div class="d-desc">{{ row.description || '暂无简介' }}</div>
      </div>

      <div class="d-section">
        <div class="d-section-title">
          题单内题目 <span class="d-count">共 {{ problems.length }} 题</span>
        </div>
        <div v-loading="loading" class="d-problems">
          <div v-for="(p, index) in problems" :key="p.id ?? index" class="dp-row">
            <span class="dp-index">{{ index + 1 }}</span>
            <span class="dp-name">#{{ p.id }} {{ p.title }}</span>
            <el-tag v-if="p.difficulty" size="small" effect="light" :type="difficultyTagType(p.difficulty)">
              {{ getDifficultyLabel(p.difficulty) }}
            </el-tag>
          </div>
          <div v-if="!loading && problems.length === 0" class="dp-empty">题单暂无题目</div>
        </div>
      </div>
    </template>

    <template #footer>
      <div class="drawer-footer">
        <el-button
          type="danger"
          plain
          class="delete-btn"
          :disabled="!row"
          @click="emit('delete', row)"
        >删除题单</el-button>
        <el-button @click="emit('preview', row)">前台预览</el-button>
        <el-button type="primary" @click="emit('edit', row)">编辑题单</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
// 详情抽屉：完整展示题单内题目，不再只给前 5 条后把用户送去编辑页。
// 难度用 Element Plus 语义标签而非纯彩色文字，浅/深主题下对比度由主题变量保证。
import { computed } from 'vue'
import { getDifficultyLabel } from '@/utils/difficulty'

const props = defineProps({
  modelValue: Boolean,
  row: { type: Object, default: null },
  problems: { type: Array, default: () => [] },
  loading: Boolean,
  full: Boolean
})
const emit = defineEmits(['update:modelValue', 'delete', 'preview', 'edit'])

const drawerSize = computed(() => (props.full ? '100%' : '520px'))

const fmtDate = (v) => (v ? String(v).slice(0, 10) : '-')

const difficultyTagType = (score) => {
  const value = Number(score)
  if (!Number.isFinite(value)) return 'info'
  if (value < 1200) return 'success'
  if (value < 1800) return 'primary'
  if (value < 2400) return 'warning'
  return 'danger'
}
</script>

<style scoped>
.drawer-head { padding-bottom: 12px; border-bottom: 1px solid var(--border-light); }
.dh-line { display: flex; align-items: center; gap: 8px; }
.dh-title { font-size: 16px; font-weight: 600; color: var(--text-primary); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dh-meta { font-size: 12px; color: var(--text-secondary); margin-top: 6px; }
.d-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 14px 0; }
.d-stat { background: var(--bg-surface-hover); border-radius: 8px; padding: 10px 12px; }
.d-label { font-size: 11px; color: var(--text-secondary); }
.d-value { font-size: 20px; font-weight: 600; color: var(--text-primary); margin-top: 2px; }
.d-section { margin-top: 16px; }
.d-section-title { font-size: 13px; font-weight: 500; color: var(--text-primary); margin-bottom: 8px; }
.d-count { font-size: 11px; font-weight: 400; color: var(--text-secondary); margin-left: 4px; }
.d-desc { font-size: 13px; line-height: 1.7; color: var(--text-regular); white-space: pre-wrap; }
.d-problems { min-height: 40px; }
.dp-row { display: flex; align-items: center; gap: 10px; padding: 8px 4px; font-size: 13px; border-bottom: 1px solid var(--border-light); }
.dp-index { width: 24px; flex-shrink: 0; font-size: 12px; color: var(--text-secondary); font-variant-numeric: tabular-nums; }
.dp-name { flex: 1; min-width: 0; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dp-empty { font-size: 12px; color: var(--text-secondary); padding: 12px 4px; }
.drawer-footer { display: flex; justify-content: flex-end; gap: 8px; }
.delete-btn { margin-right: 8px; }
</style>
