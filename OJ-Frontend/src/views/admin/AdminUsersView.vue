<template>
  <div class="admin-users">
    <header class="page-header">
      <div>
        <div class="title-line">
          <h1>用户管理</h1>
          <span class="scope-label">管理员</span>
        </div>
        <p>检索账号、查看用户概况，处理角色、封禁与密码重置。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="createVisible = true">创建账号</el-button>
    </header>

    <section class="directory-panel" aria-label="用户目录">
      <form class="query-bar" @submit.prevent="search">
        <div class="search-field">
          <label for="user-keyword">查找用户</label>
          <div class="search-control">
            <el-input
              id="user-keyword"
              v-model="draftKeyword"
              :prefix-icon="Search"
              maxlength="100"
              clearable
              placeholder="用户名 / 昵称 / 真实姓名 / 邮箱 / ID"
              @clear="search"
            />
            <el-button native-type="submit" type="primary">搜索</el-button>
          </div>
        </div>
        <div class="filter-field">
          <label for="user-role">角色</label>
          <el-select
            id="user-role"
            :model-value="query.role == null ? 'all' : String(query.role)"
            @change="changeQuery({ role: $event === 'all' ? null : Number($event), page: 1 })"
          >
            <el-option label="全部角色" value="all" />
            <el-option label="学生" value="1" />
            <el-option label="教师" value="2" />
            <el-option label="管理员" value="3" />
          </el-select>
        </div>
        <div class="filter-field">
          <label for="user-status">状态</label>
          <el-select
            id="user-status"
            :model-value="query.status"
            @change="changeQuery({ status: $event, page: 1 })"
          >
            <el-option label="全部状态" value="all" />
            <el-option label="正常" value="active" />
            <el-option label="封禁中" value="banned" />
          </el-select>
        </div>
        <el-button :icon="Download" :loading="exporting" class="export-button" @click="exportCsv">
          导出 CSV
        </el-button>
      </form>

      <div class="result-bar">
        <span role="status" aria-live="polite">
          <template v-if="loading">正在加载用户…</template>
          <template v-else-if="error && !rows.length">暂未获取用户数量</template>
          <template v-else>
            {{ hasFilters ? '筛选结果' : '用户目录' }}<b>{{ total }}</b><span class="result-unit">个用户</span>
          </template>
        </span>
        <el-button v-if="hasFilters" link type="primary" @click="resetQuery">重置筛选</el-button>
      </div>

      <div v-if="error" class="error-bar" role="alert">
        <div>
          <strong>用户列表加载失败</strong>
          <p>{{ error }}{{ rows.length ? '；下方保留上次加载的结果。' : '' }}</p>
        </div>
        <el-button :loading="loading" @click="fetchList">重试</el-button>
      </div>

      <div v-if="selectedRows.length" class="batch-bar">
        <span>已选择 {{ selectedRows.length }} 个用户</span>
        <div class="batch-actions">
          <el-button size="small" @click="openBatchRole">批量调整角色</el-button>
          <el-button size="small" type="danger" plain @click="openBatchBan(true)">批量禁用</el-button>
          <el-button size="small" type="success" plain @click="openBatchBan(false)">批量启用</el-button>
          <el-button size="small" link @click="clearSelection">取消选择</el-button>
        </div>
      </div>

      <div class="results" v-loading="loading" :aria-busy="loading">
        <UserTable
          ref="tableRef"
          :rows="rows"
          :current-user-id="currentUserId"
          :empty-text="emptyText"
          :default-sort="defaultSort"
          @detail="openDetail"
          @selection-change="handleSelectionChange"
          @sort-change="handleSortChange"
        />
      </div>

      <footer class="pager-bar">
        <el-pagination
          :current-page="query.page"
          :page-size="query.size"
          :page-sizes="USER_PAGE_SIZES"
          :total="total"
          :pager-count="5"
          :disabled="loading"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="changePage"
          @size-change="changeSize"
        />
      </footer>
    </section>

    <UserDetailDrawer
      v-model="detailVisible"
      :user-id="detailUser?.id"
      :fallback-row="detailUser"
      :current-user-id="currentUserId"
      @role="openRole"
      @ban="openBan"
      @reset="openReset"
      @audit="openAudit"
    />
    <UserCreateDialog v-model="createVisible" @created="onCreated" />
    <UserRoleDialog
      v-model="roleDialogVisible"
      :saving="roleSaving"
      :target-name="roleTarget?.username || ''"
      :current-role="Number(roleTarget?.role || 1)"
      :batch-count="roleBatchCount"
      :is-self="Number(roleTarget?.id) === currentUserId"
      @confirm="confirmRole"
    />
    <UserBanDialog
      v-model="banDialogVisible"
      :saving="banSaving"
      :banning="banMode === 'ban'"
      :batch-count="banBatchCount"
      :target-name="banTarget?.username || ''"
      @confirm="confirmBan"
    />
    <UserResetPasswordDialog
      v-model="resetDialogVisible"
      :saving="resetSaving"
      :target-name="resetTarget?.username || ''"
      @confirm="confirmReset"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Download, Plus, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  batchSetUserBanned,
  batchUpdateUserRole,
  exportAdminUsers,
  getAdminUsers,
  resetUserPassword,
  setUserBanned,
  updateUserRole,
} from '@/api/adminUser'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'
import {
  normalizeUserQuery,
  sortFromTable,
  USER_PAGE_SIZES,
  userListParams,
  usersRouteQuery,
} from '@/utils/adminUserQuery'
import { useUserStore } from '@/stores/userStore'
import UserTable from './users/UserTable.vue'
import UserDetailDrawer from './users/UserDetailDrawer.vue'
import UserCreateDialog from './users/UserCreateDialog.vue'
import UserRoleDialog from './users/UserRoleDialog.vue'
import UserBanDialog from './users/UserBanDialog.vue'
import UserResetPasswordDialog from './users/UserResetPasswordDialog.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const query = computed(() => normalizeUserQuery(route.query))
const queryKey = computed(() => JSON.stringify(query.value))
const currentUserId = computed(() => Number(userStore.userId) || null)

const draftKeyword = ref('')
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const exporting = ref(false)
const selectedRows = ref([])
const tableRef = ref(null)

const detailVisible = ref(false)
const detailUser = ref(null)
const createVisible = ref(false)

const roleDialogVisible = ref(false)
const roleSaving = ref(false)
const roleTarget = ref(null)
const roleIds = ref([])
const roleBatchCount = ref(0)

const banDialogVisible = ref(false)
const banSaving = ref(false)
const banTarget = ref(null)
const banMode = ref('ban')
const banIds = ref([])
const banBatchCount = ref(0)

const resetDialogVisible = ref(false)
const resetSaving = ref(false)
const resetTarget = ref(null)

const hasFilters = computed(() =>
  Boolean(query.value.keyword || query.value.role != null || query.value.status !== 'all'),
)
const emptyText = computed(() => (hasFilters.value ? '没有找到匹配的用户' : '暂无用户'))
const defaultSort = computed(() => ({
  prop: query.value.sortBy,
  order: query.value.order === 'asc' ? 'ascending' : 'descending',
}))

let requestId = 0
let controller

watch(
  queryKey,
  () => {
    draftKeyword.value = query.value.keyword
    clearSelection()
    const canonical = { path: route.path, query: usersRouteQuery(query.value) }
    if (router.resolve(canonical).fullPath !== route.fullPath) void router.replace(canonical)
    void fetchList()
  },
  { immediate: true },
)

async function fetchList() {
  const id = ++requestId
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const state = { ...query.value }
    const res = await getAdminUsers(userListParams(state), {
      signal: controller.signal,
      silent: true,
    })
    if (id !== requestId) return
    if (!isResultSuccess(res)) {
      error.value = getResultMessage(res, '用户列表加载失败')
      return
    }
    const data = unwrapResultData(res)
    const lastPage = Math.max(1, Math.ceil((data?.total ?? 0) / state.size))
    // 共享链接或他处删除可能让请求页超出范围，先回退到最后一页再拉取。
    if (state.page > lastPage) {
      await router.replace({ query: usersRouteQuery({ ...state, page: lastPage }) })
      return
    }
    rows.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total) || 0
  } catch (cause) {
    if (id === requestId && cause.code !== 'ERR_CANCELED') {
      showApiError(cause, '用户列表加载失败')
      error.value = cause?.message || '请稍后重试'
    }
  } finally {
    if (id === requestId) loading.value = false
  }
}

function changeQuery(patch) {
  const next = { ...query.value, ...patch }
  if (JSON.stringify(next) === JSON.stringify(query.value)) return fetchList()
  return router.push({ query: usersRouteQuery(next) })
}

function search() {
  return changeQuery({ keyword: draftKeyword.value.trim(), page: 1 })
}

function resetQuery() {
  draftKeyword.value = ''
  return changeQuery({
    keyword: '',
    role: null,
    status: 'all',
    sortBy: 'id',
    order: 'desc',
    page: 1,
  })
}

function changePage(page) {
  if (!loading.value && page !== query.value.page) return changeQuery({ page })
}

function changeSize(size) {
  if (size !== query.value.size) return changeQuery({ size, page: 1 })
}

function handleSortChange(payload) {
  return changeQuery({ ...sortFromTable(payload), page: 1 })
}

function handleSelectionChange(selection) {
  selectedRows.value = selection
}

function clearSelection() {
  selectedRows.value = []
  tableRef.value?.clearSelection()
}

function openDetail(row) {
  detailUser.value = row
  detailVisible.value = true
}

function openRole(row) {
  roleTarget.value = row
  roleIds.value = [row.id]
  roleBatchCount.value = 0
  roleDialogVisible.value = true
}

function openBatchRole() {
  if (!selectedRows.value.length) return
  roleTarget.value = null
  roleIds.value = selectedRows.value.map((row) => row.id)
  roleBatchCount.value = roleIds.value.length
  roleDialogVisible.value = true
}

async function confirmRole(role) {
  if (!roleIds.value.length) return
  roleSaving.value = true
  try {
    const res = roleBatchCount.value
      ? await batchUpdateUserRole(roleIds.value, role)
      : await updateUserRole(roleIds.value[0], role)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '角色调整失败'))
      return
    }
    ElMessage.success(roleBatchCount.value ? '批量角色已更新' : '角色已更新')
    roleDialogVisible.value = false
    detailVisible.value = false
    clearSelection()
    await fetchList()
  } catch (cause) {
    showApiError(cause, '角色调整失败')
  } finally {
    roleSaving.value = false
  }
}

function openBan(row) {
  banTarget.value = row
  banIds.value = [row.id]
  banBatchCount.value = 0
  // 已过期但字段仍为 is_banned=1 的账号也应允许清除封禁记录。
  banMode.value = row.banActive || Number(row.isBanned) === 1 ? 'unban' : 'ban'
  banDialogVisible.value = true
}

function openBatchBan(banning) {
  if (!selectedRows.value.length) return
  banTarget.value = null
  banIds.value = selectedRows.value.map((row) => row.id)
  banBatchCount.value = banIds.value.length
  banMode.value = banning ? 'ban' : 'unban'
  banDialogVisible.value = true
}

async function confirmBan({ reason, expireTime }) {
  if (!banIds.value.length) return
  const banning = banMode.value === 'ban'
  banSaving.value = true
  try {
    const payload = { banned: banning, reason, expireTime }
    const res = banBatchCount.value
      ? await batchSetUserBanned(banIds.value, payload)
      : await setUserBanned(banIds.value[0], payload)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, banning ? '禁用失败' : '启用失败'))
      return
    }
    ElMessage.success(banBatchCount.value ? (banning ? '已批量禁用' : '已批量启用') : banning ? '已禁用' : '已启用')
    banDialogVisible.value = false
    detailVisible.value = false
    clearSelection()
    await fetchList()
  } catch (cause) {
    showApiError(cause, banning ? '禁用失败' : '启用失败')
  } finally {
    banSaving.value = false
  }
}

function openReset(row) {
  resetTarget.value = row
  resetDialogVisible.value = true
}

async function confirmReset(newPassword) {
  if (!resetTarget.value?.id) return
  resetSaving.value = true
  try {
    const res = await resetUserPassword(resetTarget.value.id, newPassword)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '密码重置失败'))
      return
    }
    ElMessage.success(`已重置「${resetTarget.value.username}」的密码`)
    resetDialogVisible.value = false
    detailVisible.value = false
  } catch (cause) {
    showApiError(cause, '密码重置失败')
  } finally {
    resetSaving.value = false
  }
}

function openAudit(row) {
  if (!row?.id) return
  router.push({ name: 'AdminAuditLogs', query: { userId: String(row.id) } })
}

async function exportCsv() {
  exporting.value = true
  try {
    const { keyword, role, banned, sortBy, order } = userListParams(query.value)
    const blob = await exportAdminUsers({ keyword, role, banned, sortBy, order })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = exportFilename()
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('CSV 已导出')
  } catch (cause) {
    showApiError(cause, '导出失败')
  } finally {
    exporting.value = false
  }
}

function exportFilename() {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `用户列表-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.csv`
}

function onCreated() {
  return changeQuery({ page: 1 })
}

onBeforeUnmount(() => {
  requestId++
  controller?.abort()
})
</script>

<style scoped>
.admin-users {
  padding: 28px 28px 20px;
  gap: 24px;
  box-sizing: border-box;
  color: var(--text-primary);
  font-family: var(--font-family);
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}
.title-line {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.5px;
}
.scope-label {
  color: var(--accent-primary);
  background: var(--accent-primary-light);
  border: 1px solid var(--border-light);
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 12px;
}
.page-header p {
  margin: 9px 0 0;
  font-size: 14px;
  color: var(--text-regular);
  line-height: 1.6;
}
.directory-panel {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--bg-surface);
  overflow: hidden;
}
.query-bar {
  padding: 20px 24px;
  display: flex;
  align-items: end;
  gap: 14px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--border-light);
}
.search-field {
  flex: 1;
  min-width: 220px;
  max-width: 560px;
}
label {
  display: block;
  margin-bottom: 8px;
  color: var(--text-regular);
  font-size: 12px;
  font-weight: 500;
}
.search-control {
  display: flex;
  gap: 8px;
}
.filter-field {
  width: 140px;
  flex: 0 0 140px;
}
.export-button {
  margin-left: auto;
}
.result-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  font-size: 13px;
  color: var(--text-regular);
  min-height: 24px;
  flex-shrink: 0;
}
.result-bar b {
  font-size: 19px;
  color: var(--text-primary);
  margin: 0 6px 0 12px;
  font-variant-numeric: tabular-nums;
}
.result-unit {
  font-size: 12px;
}
.error-bar {
  margin: 0 24px 12px;
  padding: 12px 14px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: var(--radius-md);
  background: var(--el-color-danger-light-9);
  color: var(--text-regular);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-shrink: 0;
}
.error-bar strong {
  color: var(--text-primary);
}
.error-bar p {
  margin: 4px 0 0;
  font-size: 12px;
}
.batch-bar {
  margin: 0 24px 12px;
  padding: 10px 14px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  background: var(--bg-app);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-shrink: 0;
  color: var(--text-regular);
  font-size: 13px;
}
.batch-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.results {
  flex: 1;
  min-height: 120px;
  position: relative;
  overflow: hidden;
}
.pager-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 24px 14px;
  flex-shrink: 0;
}
</style>
