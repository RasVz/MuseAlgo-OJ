<template>
  <div class="admin-audit">
    <div class="page-header">
      <div>
        <div class="page-title">审计日志</div>
        <div class="page-desc">操作审计：模块、动作、操作人、耗时与结果</div>
      </div>
      <div class="filters">
        <el-input v-model="filters.userId" placeholder="用户 ID" clearable style="width: 100px" />
        <el-input v-model="filters.module" placeholder="模块" clearable style="width: 110px" />
        <el-input v-model="filters.action" placeholder="动作" clearable style="width: 130px" />
        <el-button type="primary" @click="fetchList">查询</el-button>
      </div>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="logs" size="small" border stripe height="100%">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="username" label="操作人" min-width="100" />
        <el-table-column prop="module" label="模块" width="100" />
        <el-table-column prop="action" label="动作" min-width="130" />
        <el-table-column prop="methodName" label="方法" min-width="220" show-overflow-tooltip />
        <el-table-column prop="clientIp" label="IP" width="120" />
        <el-table-column prop="executionTime" label="耗时ms" width="80" align="center" />
        <el-table-column label="结果" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">{{ row.status === 1 ? '成功' : '异常' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" min-width="150" />
      </el-table>
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="fetchList"
      />
    </el-card>
  </div>
</template>

<script setup>
// 操作审计日志（仅管理员）：只读查询页，按操作人 / 模块 / 动作过滤。
// 审计数据不可修改，页面因此不提供任何写操作，也不做前端二次确认。
import { ref, reactive, onMounted } from 'vue'
import { getAuditLogs } from '@/api/adminAuditLog'
import { unwrapResultData } from '@/utils/response'

const logs = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const filters = reactive({ userId: '', module: '', action: '' })

const fetchList = async () => {
  loading.value = true
  try {
    const res = await getAuditLogs({
      page: page.value, size: size.value,
      userId: filters.userId || undefined,
      module: filters.module || undefined,
      action: filters.action || undefined
    })
    const data = unwrapResultData(res)
    logs.value = data?.records ?? []
    total.value = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

onMounted(fetchList)
</script>

<style scoped>
.admin-audit { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.filters { display: flex; gap: 8px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.admin-audit :deep(.el-card) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.admin-audit :deep(.el-card__body) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.admin-audit :deep(.el-table) { flex: 1; min-height: 0; }
.admin-audit .pager { margin-top: auto; padding-top: 12px; }
</style>
