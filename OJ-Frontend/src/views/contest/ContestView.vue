<template>
  <div class="contest-page-container">
    <NavbarContext>
      <div class="navbar-title">
        <el-icon class="mr-2"><Trophy /></el-icon>
        全站赛事
      </div>
    </NavbarContext>

    <el-card shadow="never" class="main-card">
      <div class="table-scroll-area">
        <ContestTable
          :list="list"
          :loading="loading"
          @register="openRegisterDialog"
        />
      </div>

      <div class="pagination-footer">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="total"
          v-model:current-page="currentPage"
          :page-size="pageSize"
          @current-change="fetchData"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="registerDialogVisible"
      width="720px"
      destroy-on-close
      class="register-dialog"
    >
      <template #header>
        <div class="dialog-header">
          <h3>比赛报名确认</h3>
          <p>请认真阅读以下参赛须知，确认后方可报名成功</p>
        </div>
      </template>

      <div class="dialog-body">
        <el-alert
          title="竞赛诚信承诺"
          type="warning"
          show-icon
          :closable="false"
          class="mb-16"
        />
        <ul class="notice-list">
          <li>禁止使用 AI 工具（如 ChatGPT、Copilot、Claude 等）生成或辅助生成比赛代码。</li>
          <li>禁止与他人讨论题解、共享代码、远程协作或通过任何形式传递答案。</li>
          <li>系统将进行代码查重与行为分析，发现异常将取消成绩并视情况封禁账号。</li>
          <li>请确保独立完成比赛，遵守平台规则及赛事主办方补充说明。</li>
        </ul>

        <el-checkbox v-model="agreed">
          我已阅读并同意以上比赛规则，承诺独立完成比赛
        </el-checkbox>
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="registerDialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :disabled="!agreed"
            :loading="registering"
            @click="confirmRegister"
          >
            确认报名
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 比赛列表页：展示公开可见的比赛并支持报名。
// 报名需要勾选竞赛诚信承诺，因此用弹窗而不是直接点一下就报名。
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Trophy } from '@element-plus/icons-vue'
import NavbarContext from '@/components/NavbarContext.vue'
import ContestTable from './components/ContestTable.vue'
import { getContestList, registerContestApi } from '@/api/contest'

const loading = ref(false)
const list = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(9)

const registerDialogVisible = ref(false)
const agreed = ref(false)
const registering = ref(false)
const pendingContest = ref(null)

// visible=1 只拉公开比赛：未公开的比赛仅管理员可见，不该出现在比赛广场。
const fetchData = async () => {
  loading.value = true
  try {
    const res = await getContestList({
      currentPage: currentPage.value,
      pageSize: pageSize.value,
      visible: 1
    })

    if (res.code === 200) {
      list.value = res.data.records
      total.value = res.data.total
      // registered 补默认值：接口未下发该字段时，模板里 undefined 会被当作 falsy，
// 表现与"未报名"一致，但显式置 false 更利于后续维护判断。
      list.value.forEach(item => {
        if (item.registered === undefined) {
          item.registered = false
        }
      })
    }
  } catch (err) {
    console.error(err)
  } finally {
    loading.value = false
  }
}

// 每次打开弹窗都重置勾选：承诺不能被默认选中，否则用户可能没看清就报了名。
const openRegisterDialog = (contest) => {
  pendingContest.value = contest
  agreed.value = false
  registerDialogVisible.value = true
}

const confirmRegister = async () => {
  if (!pendingContest.value) return

  registering.value = true
  try {
    const res = await registerContestApi(pendingContest.value.id)
    if (res.code === 200) {
      ElMessage.success(`成功报名: ${pendingContest.value.title}`)
      // 报名成功后本地置位并把人数 +1，不重拉整页：
// 整个列表重新请求会让弹窗关闭的动画卡顿，且没必要。
      pendingContest.value.registered = true
      pendingContest.value.participantCount = (pendingContest.value.participantCount || 0) + 1
      registerDialogVisible.value = false
    } else {
      ElMessage.error(res.message || '报名失败')
    }
  } catch (err) {
    console.error(err)
    ElMessage.error('网络或服务器异常，请稍后再试')
  } finally {
    registering.value = false
  }
}

onMounted(() => fetchData())
</script>

<style scoped>
.contest-page-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 125px);
  overflow: hidden;
}

.navbar-title {
  font-size: 18px;
  font-weight: bold;
  color: var(--text-primary);
  display: flex;
  align-items: center;
}
.mr-2 { margin-right: 8px; }

.main-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  border: none;
  min-height: 0;
}

.main-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0;
  min-height: 0;
  overflow: hidden;
}

.table-scroll-area {
  flex: 1;
  overflow: hidden;
  padding: 0;
}

.pagination-footer {
  height: 50px;
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  border-top: 1px solid var(--border-light);
  background: var(--bg-surface);
  z-index: 5;
}

.dialog-header h3 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
}
.dialog-header p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 13px;
}
.dialog-body {
  padding: 4px 2px 8px;
}
.mb-16 {
  margin-bottom: 16px;
}
.notice-list {
  margin: 0 0 16px 0;
  padding-left: 18px;
  line-height: 1.9;
  color: var(--text-primary);
}
.notice-list li {
  margin-bottom: 4px;
}
</style>
