<template>
  <el-table
    ref="tableRef"
    :data="rows"
    row-key="id"
    height="100%"
    class="user-table"
    :default-sort="defaultSort"
    @selection-change="(selection) => emit('selection-change', selection)"
    @sort-change="(payload) => emit('sort-change', payload)"
  >
    <el-table-column type="selection" width="44" :selectable="canSelect" />
    <el-table-column prop="id" label="ID" width="80" align="center" sortable="custom" />
    <el-table-column label="用户" min-width="190">
      <template #default="{ row }">
        <div class="user-identity">
          <span class="user-name">{{ row.username || '-' }}</span>
          <span class="user-meta">
            {{ row.nickname || '未设置昵称' }}
            <span v-if="row.id === currentUserId" class="self-tag">当前账号</span>
          </span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="真实姓名" min-width="100">
      <template #default="{ row }">
        <span :class="{ muted: !row.realName }">{{ row.realName || '未填写' }}</span>
      </template>
    </el-table-column>
    <el-table-column label="邮箱" min-width="210" show-overflow-tooltip>
      <template #default="{ row }">
        <span :class="{ muted: !row.email }">{{ row.email || '未填写' }}</span>
      </template>
    </el-table-column>
    <el-table-column label="角色" width="100" align="center">
      <template #default="{ row }">
        <el-tag :type="roleTagType(row.role)" effect="light" size="small">{{ roleLabel(row.role) }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="学校" min-width="130" show-overflow-tooltip>
      <template #default="{ row }">
        <span :class="{ muted: !row.school }">{{ row.school || '未填写' }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="submitCount" label="提交数" width="95" align="center" sortable="custom">
      <template #default="{ row }">{{ row.submissionCount ?? 0 }}</template>
    </el-table-column>
    <el-table-column label="AI 用量" width="105" align="center">
      <template #default="{ row }">{{ Number(row.aiTokens ?? 0).toLocaleString() }}</template>
    </el-table-column>
    <el-table-column label="状态" width="115" align="center">
      <template #default="{ row }">
        <el-tooltip :disabled="!banSummary(row)" :content="banSummary(row)" placement="top">
          <el-tag :type="userStatusMeta(row).type" effect="light" size="small">
            {{ userStatusMeta(row).label }}
          </el-tag>
        </el-tooltip>
      </template>
    </el-table-column>
    <el-table-column prop="createTime" label="注册时间" width="150" sortable="custom">
      <template #default="{ row }">{{ formatUserTime(row.createTime) }}</template>
    </el-table-column>
    <el-table-column prop="lastLoginTime" label="最后登录" width="150" sortable="custom">
      <template #default="{ row }">{{ formatUserTime(row.lastLoginTime) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="90" align="center" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="emit('detail', row)">详情</el-button>
      </template>
    </el-table-column>
    <template #empty>
      <div class="table-empty">{{ emptyText }}</div>
    </template>
  </el-table>
</template>

<script setup>
import { ref } from 'vue'
import { banSummary, formatUserTime, roleLabel, userStatusMeta } from '@/utils/adminUserQuery'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  currentUserId: { type: [Number, String], default: null },
  emptyText: { type: String, default: '暂无用户' },
  defaultSort: { type: Object, default: () => ({}) },
})

const emit = defineEmits(['detail', 'selection-change', 'sort-change'])
const tableRef = ref(null)

function canSelect(row) {
  return row.id !== props.currentUserId
}

function clearSelection() {
  tableRef.value?.clearSelection()
}

function roleTagType(role) {
  const value = Number(role)
  if (value === 3) return 'danger'
  if (value === 2) return 'warning'
  return 'info'
}

defineExpose({ clearSelection })
</script>

<style scoped>
.user-table {
  width: 100%;
}
.user-table :deep(th.el-table__cell) {
  background: var(--bg-app);
  color: var(--text-regular);
  font-weight: 500;
  font-size: 12px;
  height: 42px;
}
.user-table :deep(.el-table__cell:first-child .cell) {
  padding-left: 16px;
}
.user-identity {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.user-name {
  color: var(--text-primary);
  font-weight: 550;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-meta {
  color: var(--text-regular);
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.self-tag {
  flex: 0 0 auto;
  color: var(--accent-primary);
  background: var(--accent-primary-light);
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 11px;
}
.muted {
  color: var(--text-secondary);
}
.table-empty {
  padding: 36px 0;
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
