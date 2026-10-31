<template>
  <div class="class-layout">
    <NavBarContext>
      <div class="class-header-row">
        
        <div class="nav-menu-wrapper">
          <el-menu :default-active="activeTab" mode="horizontal" router :ellipsis="false" class="transparent-menu">
            <el-menu-item :index="`/class/${classId}`">目录</el-menu-item>
            <el-menu-item :index="`/class/${classId}/homework`">作业</el-menu-item>
            <el-menu-item :index="`/class/${classId}/problem/list`">题单</el-menu-item>
            <el-menu-item :index="`/class/${classId}/contest`">比赛</el-menu-item>
            <el-menu-item :index="`/class/${classId}/post`">动态</el-menu-item>
          </el-menu>
        </div>

        <div class="header-right">
          <el-tooltip content="切换到教师管理后台" placement="bottom" v-if="isTeacher">
            <el-button 
              type="primary" 
              plain 
              round 
              size="small" 
              icon="Setting"
              @click="handleGoAdmin(classId)"
            >
              管理视图
            </el-button>
          </el-tooltip>

          <span class="class-title" :title="classInfo?.name || '加载中...'">
            {{ classInfo?.name || '加载中...' }}
          </span>
        </div>

      </div>
    </NavBarContext>

    <div class="class-content">
      <router-view />
    </div>
  </div>
</template>

<script setup>
// 班级详情页（学生视角）：班级信息 + 课件目录 + 作业 + 题单 + 讨论区入口。
// 班级上下文（classStore）由布局注入，本页从 store 读取以免重复请求。
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavBarContext from '@/components/NavbarContext.vue'
import { getClassDetail } from '@/api/class'
import { useClassContextStore } from '@/stores/classContext'
import { useUserStore } from '@/stores/userStore'

const route = useRoute()
const router = useRouter()
const classStore = useClassContextStore()
const userStore = useUserStore()

// 路由参数名在不同路由下可能是 classId 或 id，两个都兜住。
const classId = route.params.classId || route.params.id
const classInfo = ref({})

// 高亮的 Tab 直接由当前路径决定：子路由各自就是唯一真源，
// 另存一份 activeTab 会与路由不同步。
const activeTab = computed(() => route.path)

const currentUserId = computed(() => userStore.userId)

// 是否为本班教师：决定是否显示「管理视图」入口。
// 判断依据是"是不是这个班的 teacherId"，而不是 role>=2 ——
// 教师也能是别的班的学生，在自己班外不该出现管理入口。
// ID 统一转 String 比较，防止路由参数与 store 里的类型不一致。
const isTeacher = computed(() => {
  if (!classInfo.value || !currentUserId.value) return false
  return String(classInfo.value.teacherId) === String(currentUserId.value)
})

const fetchClassInfo = async () => {
  if (classStore.currentClass && String(classStore.currentClass.id) === String(classId)) {
    classInfo.value = classStore.currentClass
    return
  }

  try {
    const res = await getClassDetail(classId)
    if (res.code === 200) {
      classInfo.value = res.data
      classStore.setClass(res.data)
    }
  } catch (error) {
    console.error('获取班级详情失败', error)
  }
}

// 跳转到管理页
const handleGoAdmin = (classId) => {
  router.push({ name: 'AdminClassDetail', params: { classId } })
}

onMounted(() => {
  fetchClassInfo()
})
</script>

<style scoped>
.class-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.class-header-row {
  display: flex;
  align-items: center;
  width: 100%;
  height: 100%;
}

/* 班级名称 — 右侧展示，紧邻管理视图按钮和用户头像 */
.class-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 260px;
}

.nav-menu-wrapper {
  flex-shrink: 0;
}

/* 新增：右侧区域，利用 margin-left: auto 自动推到最右 */
.header-right {
  margin-left: auto;
  padding-right: 20px; /* 离右边框一点距离 */
  display: flex;
  align-items: center;
  gap: 12px;
  z-index: 10; /* 确保层级高于 absolute 的标题 */
}

.transparent-menu {
  background-color: transparent;
  border-bottom: none;
  min-width: 37em;
}
.transparent-menu :deep(.el-menu-item) {
  height: 60px;
  line-height: 60px;
  background-color: transparent !important;
  font-size: 15px;
  padding: 0 15px;
}
.transparent-menu :deep(.el-menu-item.is-active) {
  border-bottom: 2px solid var(--accent-primary);
  color: var(--accent-primary);
  font-weight: 600;
}
.transparent-menu :deep(.el-menu-item:hover) {
  color: var(--accent-primary);
  background-color: var(--accent-primary-light) !important;
}

.class-content {
  flex: 1;
  padding-right: 10px;
  overflow-y: auto;
}
</style>
