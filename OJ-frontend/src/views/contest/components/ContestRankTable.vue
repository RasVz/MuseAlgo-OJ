<template>
  <el-card shadow="never" class="table-card" v-loading="loading">
    <el-alert
      v-if="errorText"
      :title="errorText"
      type="warning"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <el-alert
      v-if="oiRankingHidden"
      title="OI 赛制比赛中隐藏排行榜，比赛结束后公布最终排名。"
      type="info"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <el-table
      v-if="!oiRankingHidden"
      :data="rankList"
      border
      stripe
      style="width: 100%"
      empty-text="暂无排行榜数据"
    >
      <el-table-column label="名次" width="150" align="center">
        <template #default="{ row, $index }">
          <div class="rank-cell">
            <el-tag
              v-if="getRankValue(row, $index) === 1"
              type="warning"
              effect="dark"
            >
              #1
            </el-tag>
            <el-tag
              v-else-if="getRankValue(row, $index) === 2"
              type="success"
              effect="dark"
            >
              #2
            </el-tag>
            <el-tag
              v-else-if="getRankValue(row, $index) === 3"
              type="danger"
              effect="dark"
            >
              #3
            </el-tag>
            <span v-else>#{{ getRankValue(row, $index) }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="用户" min-width="250">
        <template #default="{ row }">
          <div class="user-cell">
            <el-avatar :size="36" :src="row.userAvatar || row.avatar || ''">
              {{ getUserDisplayName(row).slice(0, 1) }}
            </el-avatar>
            <div class="user-meta">
              <div class="username">{{ getUserDisplayName(row) }}</div>
              <div class="userid">UID: {{ row.userId ?? '-' }}</div>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column v-if="!isOiContest" label="解题数" width="180" align="center">
        <template #default="{ row }">
          {{ row.solvedCount ?? 0 }}
        </template>
      </el-table-column>

      <el-table-column v-if="!isOiContest" label="罚时(分钟)" width="180" align="center">
        <template #default="{ row }">
          {{ row.penaltyTime ?? 0 }}
        </template>
      </el-table-column>

      <el-table-column v-if="isOiContest" label="总分" width="180" align="center">
        <template #default="{ row }">
          {{ row.totalScore ?? 0 }}
        </template>
      </el-table-column>
    </el-table>

    <el-empty
      v-if="!oiRankingHidden && !loading && rankList.length === 0 && !errorText"
      description="暂无排行榜数据"
    />
  </el-card>
</template>

<script setup>
// 榜单表格：受控展示组件，全部数据由 ContestRankingView 通过 props 传入
// （实时榜走 SSE 推送，最终榜走一次性请求，表格本身不区分两者）。
defineProps({
  rankList: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  errorText: { type: String, default: '' },
  oiRankingHidden: { type: Boolean, default: false },
  isOiContest: { type: Boolean, default: false }
})

// 榜单里匿名的用户（OI 赛制封榜期）也可能占行，兜底用 userId 拼一个可辨识的名字，
// 否则整行用户名空白，用户无法判断那是哪一行。
function getUserDisplayName(row) {
  return row?.username || row?.nickname || (row?.userId ? `用户${row.userId}` : '未知用户')
}

// 并列名次时后端可能不下发 rankNum（或下发 0），此时退回按行序展示，
// 保证名次列不会出现空白。
function getRankValue(row, index) {
  const rank = Number(row?.rankNum)
  return Number.isFinite(rank) && rank > 0 ? rank : index + 1
}
</script>

<style scoped>
.table-card {
  margin-bottom: 16px;
}

.mb-16 {
  margin-bottom: 16px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-meta {
  min-width: 0;
}

.username {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  word-break: break-all;
}

.userid {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 2px;
}

.rank-cell {
  font-weight: 600;
}
</style>
