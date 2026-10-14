<template>
  <el-table
    v-loading="loading"
    :data="list"
    style="width: 100%"
    :header-cell-style="{ background: 'var(--bg-input)', color: 'var(--text-regular)' }"
  >
    <el-table-column prop="id" label="#" width="90" align="center" />

    <el-table-column label="用户" min-width="180">
      <template #default="{ row }">
        <div class="user-cell hover-link" style="cursor: pointer;" @click.stop="$emit('view-user', row.userId)">
          <el-avatar :size="24" :src="row.userAvatar">
            {{ (row.username || 'U').charAt(0) }}
          </el-avatar>
          <span class="username">{{ row.username || `User#${row.userId || '-'}` }}</span>
        </div>
      </template>
    </el-table-column>

    <el-table-column label="题目" min-width="220">
      <template #default="{ row }">
        <span class="hover-link" style="cursor: pointer;" @click.stop="$emit('view-problem', row.problemId)">
          {{ formatProblem(row) }}
        </span>
      </template>
    </el-table-column>

    <el-table-column label="结果" width="170" align="center">
      <template #default="{ row }">
        <el-tag :type="statusTagType(row.status)" effect="plain">
          {{ statusText(row.status) }}
        </el-tag>
      </template>
    </el-table-column>

    <el-table-column label="语言" width="130" align="center">
      <template #default="{ row }">
        <span class="mono">{{ row.language || '-' }}</span>
      </template>
    </el-table-column>

    <el-table-column label="提交时间" width="180" align="center">
      <template #default="{ row }">
        <span class="mono">{{ formatTime(row.createTime) }}</span>
      </template>
    </el-table-column>

    <el-table-column label="查看" width="120" align="center" fixed="right">
      <template #default="{ row }">
        <el-button
          type="primary"
          link
          :disabled="!canViewCode(row)"
          @click.stop="$emit('view-detail', row)"
        >
          代码详情
        </el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup>
// 比赛提交记录表：受控展示组件。
// 比赛期间只能看自己的代码（OI/ACM 赛制都禁止赛中查他人代码以防抄袭），
// 比赛结束后对所有人开放——见 canViewCode。
import dayjs from 'dayjs'

const props = defineProps({
  list: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  isContestEnded: { type: Boolean, default: false },
  myUserId: { type: [Number, String], default: null }
})

defineEmits(['view-user', 'view-problem', 'view-detail'])

// 判题状态码与后端 JudgeStatus 一致。null/undefined 表示"刚提交还没进队列"，
// 与 0（Pending）分开显示：前者是还没判，后者是在判。
const statusText = (s) => ({
  0: 'Pending',
  1: 'AC',
  2: 'WA',
  3: 'CE',
  4: 'RE',
  5: 'TLE'
}[s === null || s === undefined ? 0 : Number(s)] || (s === null || s === undefined ? '已提交' : 'Unknown'))

const statusTagType = (s) => ({
  1: 'success',
  2: 'danger',
  3: 'warning',
  4: 'warning',
  5: 'warning',
  0: 'info'
}[s === null || s === undefined ? 0 : Number(s)] || 'info')

const formatTime = (t) => (t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-')

const formatProblem = (row) => {
  const pid = row.problemId ?? '-'
  const title = row.problemTitle || ''
  return title ? `P${pid} ${title}` : `P${pid}`
}

// 赛中只允许看自己的代码，赛后全开放。userId 比较必须转数字：
// props 传进来的 myUserId 可能是字符串，与数字型的 row.userId 直接 === 恒为 false，
// 会导致赛中连自己的代码都点不开。
const canViewCode = (row) => {
  if (props.isContestEnded) return true
  const myId = props.myUserId
  if (myId === null || myId === undefined) return false
  return Number(row.userId) === Number(myId)
}
</script>

<style scoped>
.user-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.username {
  font-size: 14px;
  color: var(--text-primary);
  font-weight: 500;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
}
</style>
