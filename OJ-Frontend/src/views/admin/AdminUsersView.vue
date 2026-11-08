<template>
  <div class="admin-users">
    <div class="page-header">
      <div>
        <div class="page-title">用户管理</div>
        <div class="page-desc">用户搜索、创建账号、角色调整、禁用与重置密码（禁用后登录与所有请求被拒绝）</div>
      </div>
      <div class="header-right">
        <el-input v-model="keyword" placeholder="用户名 / 昵称 / ID" clearable style="width: 240px" @keyup.enter="fetchList" />
        <el-button type="primary" @click="fetchList">搜索</el-button>
        <el-button type="primary" icon="Plus" @click="openCreate">创建账号</el-button>
      </div>
    </div>

    <el-card shadow="never" v-loading="loading" class="users-card">
      <el-table :data="users" border stripe height="100%" :default-sort="{ prop: 'id', order: 'descending' }" @sort-change="handleSortChange">
        <el-table-column prop="id" label="ID" width="70" align="center" sortable="custom" />
        <el-table-column label="用户名" min-width="160" class-name="nowrap-cell">
          <template #default="{ row }">{{ row.username }}</template>
        </el-table-column>
        <el-table-column label="昵称" min-width="160" class-name="nowrap-cell">
          <template #default="{ row }">{{ row.nickname || '-' }}</template>
        </el-table-column>
        <el-table-column label="真实姓名" min-width="100">
          <template #default="{ row }">{{ row.realName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="220" class-name="nowrap-cell">
          <template #default="{ row }">{{ row.email || '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="130" align="center">
          <template #default="{ row }">
            <el-select :model-value="row.role" size="small" @change="(v) => changeRole(row, v)">
              <el-option label="学生" :value="1" />
              <el-option label="教师" :value="2" />
              <el-option label="管理员" :value="3" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column prop="school" label="学校" min-width="120">
          <template #default="{ row }">{{ row.school || '-' }}</template>
        </el-table-column>
        <el-table-column prop="submissionCount" label="提交数" width="90" align="center" sortable="custom" />
        <el-table-column label="AI 用量" width="110" align="center" sortable="custom">
          <template #default="{ row }">{{ Number(row.aiTokens ?? 0).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="注册时间" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ (row.createTime || '').slice(0, 16) || '-' }}</template>
        </el-table-column>
        <el-table-column label="最后登录" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ (row.lastLoginTime || '').slice(0, 16) || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isBanned === 1 ? 'danger' : 'success'" size="small">
              {{ row.isBanned === 1 ? '已禁用' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" class-name="op-cell">
          <template #default="{ row }">
            <el-button
              :type="row.isBanned === 1 ? 'success' : 'danger'"
              size="small"
              link
              @click="toggleBan(row)"
            >
              {{ row.isBanned === 1 ? '启用' : '禁用' }}
            </el-button>
            <el-button type="warning" size="small" link @click="openResetPassword(row)">重置密码</el-button>
          </template>
        </el-table-column>
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

    <!-- 创建账号 -->
    <el-dialog v-model="createVisible" title="创建账号" width="480px" :close-on-click-modal="false">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="登录名，创建后不可修改" maxlength="32" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="createForm.nickname" placeholder="展示名称" maxlength="32" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input v-model="createForm.password" type="password" show-password placeholder="至少 6 位" maxlength="32" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-radio-group v-model="createForm.role">
            <el-radio :value="1">学生</el-radio>
            <el-radio :value="2">教师</el-radio>
            <el-radio :value="3">管理员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="createForm.email" placeholder="选填，用于找回密码" maxlength="64" />
        </el-form-item>
        <el-form-item label="学校" prop="school">
          <el-input v-model="createForm.school" placeholder="选填" maxlength="64" />
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="createForm.realName" placeholder="选填" maxlength="32" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetVisible" :title="`重置密码：${resetTarget?.username || ''}`" width="420px" :close-on-click-modal="false">
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="90px">
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="resetForm.newPassword" type="password" show-password placeholder="至少 6 位" maxlength="32" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="resetForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" maxlength="32" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" title="重置后该用户需使用新密码登录" class="mb12" />
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 用户管理（仅管理员）：账号检索、角色调整、启用禁用，以及创建账号与重置密码。
//
// 角色语义：1=学生、2=教师、3=管理员，权限判断一律以这个数值为准。
// 角色变更与禁用会立刻影响目标用户的登录态与全部接口权限，因此都带二次确认。
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAdminUsers, updateUserRole, setUserBanned,
  createAdminUser, resetUserPassword
} from '@/api/adminUser'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'

const users = ref([])
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const size = ref(11)
const sortBy = ref('id')
const sortOrder = ref('desc')
const total = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const res = await getAdminUsers({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      sortBy: sortBy.value,
      order: sortOrder.value
    })
    const data = unwrapResultData(res)
    users.value = data?.records ?? []
    total.value = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

const handleSortChange = ({ prop, order }) => {
  // 取消排序时回到默认：ID 从高到低
  sortBy.value = order ? prop : 'id'
  sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  fetchList()
}

const changeRole = async (row, role) => {
  const labels = { 1: '学生', 2: '教师', 3: '管理员' }
  // 先确认再改：角色是权限的唯一来源，降级操作会让目标用户立刻失去正在使用的能力。
  try {
    await ElMessageBox.confirm(
      `确定将用户「${row.username}」的角色调整为${labels[role] || role}？`,
      '角色调整确认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  // 失败后重新拉列表：角色可能已被后端部分应用，本地那行数据不可信。
  const res = await updateUserRole(row.id, role)
  if (!isResultSuccess(res)) {
    ElMessage.error(getResultMessage(res, '角色更新失败'))
    fetchList()
    return
  }
  row.role = role
  ElMessage.success('角色已更新')
}

const toggleBan = async (row) => {
  // isBanned 是 0/1 而非布尔，禁用态取反要先做一次比较转换
  const banned = row.isBanned !== 1
  await ElMessageBox.confirm(
    banned ? `确定禁用用户「${row.username}」？其登录与所有请求将立即被拒绝。` : `确定启用用户「${row.username}」？`,
    banned ? '禁用确认' : '启用确认',
    { type: 'warning' }
  )
  const res = await setUserBanned(row.id, banned)
  if (!isResultSuccess(res)) {
    ElMessage.error(getResultMessage(res, '操作失败'))
    return
  }
  ElMessage.success(banned ? '已禁用' : '已启用')
  fetchList()
}

// ===== 创建账号 =====
const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref(null)
const createForm = reactive({ username: '', nickname: '', password: '', role: 1, email: '', school: '', realName: '' })
// 用户名规则与注册页保持一致（3-32 位字母数字下划线）：
    // 管理员建号走的是同一套认证逻辑，这里不校验会创建出永远登不上的账号。
const createRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{3,32}$/, message: '3-32 位字母/数字/下划线', trigger: 'blur' }
  ],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码至少 6 位', trigger: 'blur' }
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

const openCreate = () => {
  createForm.username = ''
  createForm.nickname = ''
  createForm.password = ''
  createForm.role = 1
  createForm.email = ''
  createForm.school = ''
  createForm.realName = ''
  createVisible.value = true
}

const submitCreate = async () => {
  try {
    await createFormRef.value.validate()
  } catch {
    return
  }
  creating.value = true
  try {
    const res = await createAdminUser({ ...createForm })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '创建失败'))
      return
    }
    ElMessage.success(`账号「${createForm.username}」创建成功`)
    createVisible.value = false
    fetchList()
  } finally {
    creating.value = false
  }
}

// ===== 重置密码 =====
const resetVisible = ref(false)
const resetting = ref(false)
const resetTarget = ref(null)
const resetFormRef = ref(null)
const resetForm = reactive({ newPassword: '', confirmPassword: '' })
const resetRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== resetForm.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

const openResetPassword = (row) => {
  resetTarget.value = row
  resetForm.newPassword = ''
  resetForm.confirmPassword = ''
  resetVisible.value = true
}

const submitReset = async () => {
  try {
    await resetFormRef.value.validate()
  } catch {
    return
  }
  resetting.value = true
  try {
    const res = await resetUserPassword(resetTarget.value.id, resetForm.newPassword)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '重置失败'))
      return
    }
    ElMessage.success(`已重置「${resetTarget.value.username}」的密码`)
    resetVisible.value = false
  } finally {
    resetting.value = false
  }
}

onMounted(fetchList)
</script>

<style scoped>
/* 全视口 flex 布局：页面本身不滚动，表格内部滚动 */
.admin-users {
  padding: 16px;
  box-sizing: border-box;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 10px; flex-shrink: 0; }
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.header-right { display: flex; gap: 8px; }
.admin-users :deep(.users-card) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.admin-users :deep(.users-card .el-card__body) { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: 20px 20px 8px; }
.admin-users :deep(.el-table) { flex: 1; min-height: 0; }
.pager { margin-top: 8px; justify-content: center; flex-shrink: 0; }
.admin-users :deep(.op-cell .cell) { white-space: nowrap; }
.admin-users :deep(.nowrap-cell .cell) { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.mb12 { margin-bottom: 12px; }
</style>
