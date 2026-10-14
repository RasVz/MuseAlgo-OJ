<template>
  <div class="course-admin admin-scroll-page">
    <div class="admin-top-bar">
      <div class="nav-left">
        <BackButton
          style="position: static; transform: none; box-shadow: none; border: none; background: transparent;"
          default-path="/admin/class"
        />
        <span style="border-right: 2px solid var(--border-base); height: 20px;"></span>
        <span> &nbsp;&nbsp;&nbsp; </span>
      </div>

      <div class="nav-center">
        <el-tabs v-model="activeTab" class="custom-tabs" @tab-click="handleTabClick">
          <el-tab-pane label="成员管理" name="member" />
          <el-tab-pane label="课件资料" name="courseware" />
          <el-tab-pane label="作业管理" name="homework" />
          <el-tab-pane label="题单管理" name="problemlist" />
          <el-tab-pane label="比赛管理" name="contest" />
          <el-tab-pane label="班级学情解读" name="insight" />
          <!-- <el-tab-pane label="公告发布" name="announcement" /> -->
        </el-tabs>
      </div>

      <div class="nav-right">
        <el-button type="warning" plain round size="small" icon="Key" @click="handleGenerateCode">生成邀请码</el-button>
        <el-dialog v-model="codeVisible" style="top:12%;" width="300px" center>
          <div style="text-align: center;">
            <p style="color: black; font-size: 20px;">班级邀请码</p>
            <div style="font-size: 32px; font-weight: bold; letter-spacing: 4px; color: var(--accent-primary); margin: 20px 0;">
              {{ inviteCode }}
            </div>
            <p style="color: var(--text-regular); font-size: 14px;">邀请码 5 分钟内有效</p>
          </div>
        </el-dialog>

        <el-button type="primary" round size="small" icon="TopRight" @click="$router.push({ name: 'ClassDirectory', params: { id: classId } })">
          进入班级视图
        </el-button>

        <span v-if="className" class="class-name">{{ className }}</span>
      </div>
    </div>

    <div class="admin-content">
      <el-card v-if="activeTab === 'member'" shadow="never">
        <template #header>
          <div style="display: flex; align-items: center; gap: 8px;">
            <span>学生名单管理</span>
          </div>
        </template>
        <ClassMemberManage :class-id="classId" />
      </el-card>

      <el-card v-else-if="activeTab === 'courseware'" shadow="never">
        <template #header>
          <span>班级课件管理</span>
        </template>
        <ClassCoursewareManage :class-id="classId" />
      </el-card>

      <el-card v-else-if="activeTab === 'homework'" shadow="never">
        <ClassHomeworkManage :class-id="classId" />
      </el-card>

      <el-card v-else-if="activeTab === 'problemlist'" shadow="never">
        <template #header>
          <span>班级题单管理</span>
        </template>
        <ClassProblemListManager :class-id="classId" />
      </el-card>

      <el-card v-else-if="activeTab === 'contest'" shadow="never">
        <template #header>
          <span>班级比赛管理</span>
        </template>
        <ClassContestManager :class-id="classId" />
      </el-card>

      <ClassInsightPanel v-else-if="activeTab === 'insight'" :class-id="classId" />

      <!-- <el-card v-else-if="activeTab === 'announcement'" shadow="never">
        公告模块正在建设中...
      </el-card> -->
    </div>
  </div>
</template>

<script setup>
// 班级管理主页：把一个班级的全部管理动作收进六个 Tab
// （成员 / 课件 / 作业 / 题单 / 比赛 / 学情洞察），本页只负责 Tab 编排与邀请码。
// Tab 名同步到路由 query，这样刷新或分享链接能回到同一栏目，浏览器前进后退也可用。
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getClassDetail, getClassInviteCode } from '@/api/class'
import BackButton from '@/components/BackButton.vue'
import ClassMemberManage from './components/ClassMemberManage.vue'
import ClassHomeworkManage from './components/ClassHomeworkManage.vue'
import ClassCoursewareManage from './components/ClassCoursewareManage.vue'
import ClassProblemListManager from './components/ClassProblemListManager.vue'
import ClassContestManager from './components/ClassContestManager.vue'
import ClassInsightPanel from './components/ClassInsightPanel.vue'

const route = useRoute()
const router = useRouter()
const classId = route.params.classId
const className = ref('')

const TAB_NAMES = ['member', 'courseware', 'homework', 'problemlist', 'contest', 'insight']
// 未知 tab 值一律回落到成员管理，而不是让页面渲染出一个空白 Tab。
const normalizeTab = (tab) => (TAB_NAMES.includes(tab) ? tab : 'member')

const activeTab = ref(normalizeTab(route.query.tab)) // 默认进入成员管理，并支持从路由恢复当前栏目

// 路由是唯一真源：用户手动改地址栏或点击后退时，Tab 必须跟着路由走，
// 否则会出现「地址栏是 homework、界面显示成员」的不一致。
watch(
  () => route.query.tab,
  (tab) => {
    activeTab.value = normalizeTab(tab)
  }
)

// 用 replace 而非 push：切 Tab 不该在历史里堆记录，
// 否则用户按一次后退要连点十几次才能离开这个页面。
const handleTabClick = (tabPane) => {
  const nextTab = normalizeTab(tabPane?.paneName)
  if (nextTab === activeTab.value && route.query.tab === nextTab) return

  activeTab.value = nextTab
  router.replace({
    query: {
      ...route.query,
      tab: nextTab
    }
  })
}

const codeVisible = ref(false)
const inviteCode = ref('')

// 生成邀请码
const handleGenerateCode = async () => {
  try {
    const res = await getClassInviteCode(classId)
    if (res.code === 200) {
      inviteCode.value = res.data
      codeVisible.value = true
    }
  } catch (error) {
    console.error(error)
  }
}

// 获取班级名称
onMounted(async () => {
  try {
    const res = await getClassDetail(classId)
    if (res.code === 200 && res.data) {
      className.value = res.data.name || res.data.title || ''
    }
  } catch {
    // 静默失败，班级名称非必要
  }
})
</script>

<style scoped>
.course-admin { background: var(--bg-surface-hover); padding: 16px; box-sizing: border-box; }
.admin-top-bar { display: flex; align-items: center; width: 100%; height: 60px; background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: 12px; padding: 0 20px; box-sizing: border-box; }

.nav-left { display: flex; align-items: center; color: var(--text-regular); flex-shrink: 0; }

.nav-center { flex: 1; display: flex; min-width: 0; }
.custom-tabs :deep(.el-tabs__header) { margin: 0; border-bottom: none; }
.custom-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }
.custom-tabs :deep(.el-tabs__item) { height: 60px; line-height: 60px; font-size: 15px; }

.nav-right { display: flex; align-items: center; gap: 10px; margin-left: auto; flex-shrink: 0; }
.class-name { font-weight: 600; font-size: 15px; color: var(--text-primary); white-space: nowrap; }

.admin-content { padding: 16px 0 0; }
</style>
