<template>
  <el-card shadow="never" class="detail-card">
    <template #header>
      <div class="detail-header">
        <div class="detail-title-wrap">
          <span>提交记录</span>
          <el-tag :type="tagType">{{ modeLabel }}</el-tag>
        </div>
        <div class="detail-actions">
          <el-button size="small" @click="$emit('refresh', page)">刷新记录</el-button>
          <el-button size="small" @click="$emit('reset')">重置筛选</el-button>
        </div>
      </div>
    </template>

    <div class="detail-summary">{{ summaryText }}</div>

    <el-table
      :data="list"
      style="width: 100%"
      v-loading="loading"
      highlight-current-row
      :row-class-name="rowClassFn"
      @row-click="handleRowClick"
      :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)', fontWeight: 'bold' }"
    >
      <el-table-column prop="id" label="提交ID" width="100" />
      <el-table-column label="提交时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column prop="studentName" label="学生" min-width="120" />
      <el-table-column prop="problemTitle" label="题目" min-width="180" show-overflow-tooltip />
      <el-table-column prop="language" label="语言" min-width="100" />
      <el-table-column label="结果" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="score" label="得分" width="80" align="center" />
      <el-table-column label="耗时" width="100" align="center">
        <template #default="{ row }">{{ formatRunTime(row.runTime) }}</template>
      </el-table-column>
      <el-table-column label="内存" width="100" align="center">
        <template #default="{ row }">{{ formatRunMemory(row.runMemory) }}</template>
      </el-table-column>
    </el-table>

    <div class="pagination-box">
      <el-pagination
        background layout="total, prev, pager, next"
        :total="total" :page-size="pageSize"
        v-model:current-page="pageModel"
        @current-change="page => $emit('page-change', page)"
      />
    </div>
  </el-card>
</template>

<script setup>
// 作业提交记录表：只负责呈现与翻页，不持有数据源。
// 父组件经 props 传入 list/total/page，翻页意图以 page-change 事件抛回，
// 请求也由父组件发起——同一份查询条件在图表与表格之间共享，避免口径不一致。
import dayjs from 'dayjs'

const props = defineProps({
  list: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  loading: { type: Boolean, default: false },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 },
  modeLabel: { type: String, default: '全部记录' },
  tagType: { type: String, default: 'info' },
  summaryText: { type: String, default: '' }
})

defineEmits(['refresh', 'reset', 'page-change', 'row-click'])

// 分页器只读绑定（set 是空实现）：页码的真源在父组件，
// 这里若允许本地修改会出现"表格显示第 3 页、数据其实是第 2 页"的错位。
const pageModel = computed({
  get: () => props.page,
  set: () => {}
})

import { computed } from 'vue'

const formatDateTime = v => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-'
const formatRunTime = v => (v === null || v === undefined || v === '') ? '-' : `${v} ms`
const formatRunMemory = v => (v === null || v === undefined || v === '') ? '-' : `${v} KB`

// 判题状态码与后端 JudgeStatus 枚举一一对应，0=待评测、1=AC、2=WA、3=CE、4=RE、5=TLE。
// 后端还会返回 6（系统错误）等，此处落到「未知」而不是错误地显示成某个具体判题结果。
const statusText = (status) => {
  const code = Number(status) || -1
  const map = { 0: '待评测', 1: 'AC', 2: 'WA', 3: 'CE', 4: 'RE', 5: 'TLE' }
  return map[code] || '未知'
}

const statusTagType = (status) => {
  const code = Number(status) || -1
  const map = { 1: 'success', 2: 'danger', 3: 'info', 4: 'warning', 5: 'warning' }
  return map[code] || ''
}

const rowClassFn = () => 'submission-row-clickable'

// 提交详情在新标签页打开：教师查看记录常常需要来回对照多个学生的提交。
const handleRowClick = (row) => {
  const base = import.meta.env.BASE_URL || '/'
  const normalizedBase = base.endsWith('/') ? base : `${base}/`
  const url = new URL(`${normalizedBase}submission/${row.id}`, window.location.origin).href
  window.open(url, '_blank')
}
</script>

<style scoped>
.detail-card { border-radius: 12px; }
.detail-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.detail-title-wrap, .detail-actions { display: flex; align-items: center; gap: 10px; }
.detail-summary { margin-bottom: 14px; font-size: 13px; color: var(--text-regular); }
.pagination-box { display: flex; justify-content: center; margin-top: 18px; }
:deep(.submission-row-clickable > td) { cursor: pointer; }
:deep(.submission-row-clickable:hover > td) { background: var(--accent-primary-light) !important; }
</style>
