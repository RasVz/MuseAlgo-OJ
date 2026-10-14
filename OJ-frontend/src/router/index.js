import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { useAuthGuard } from '@/composables/useAuthGuard'
import { beginRouteTrack, trackRoute } from '@/utils/metrics'

// =============================================================================
// 1. 基础页面与认证模块 (无 Layout 或 特殊 Layout)
// =============================================================================
const authRoutes = [
  { path: '/login', name: 'Login', component: () => import('@/views/auth/LoginView.vue'), meta: { title: '登录' } },
  { path: '/register', name: 'Register', component: () => import('@/views/auth/RegisterView.vue'), meta: { title: '注册' } },
]

const studyRoutes = [
  {
    path: '/study/:classId/:coursewareId?',
    name: 'CourseStudy',
    component: () => import('@/views/study/CourseStudyView.vue'),
    meta: { title: '课程学习', requiresAuth: true }
  }
]

const standaloneRoutes = [
  {
    path: '/admin/courseware/create',
    name: 'AdminCoursewareCreate',
    component: () => import('@/views/admin/CoursewareEditorView.vue'),
    meta: { title: '录入新课件', requiresAuth: true }
  }
]

// 404 兜底：未匹配路由显示友好页面
const notFoundRoute = [
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/error/NotFoundView.vue'), meta: { title: '页面未找到' } }
]

// =============================================================================
// 2. 主业务模块 (BasicLayout 下的子路由)
// =============================================================================

// 2.1 公共/核心页面
const coreRoutes = [
  { path: '/', name: 'Home', component: () => import('@/views/home/HomeView.vue'), meta: { title: '首页' } },
  { path: 'ranking', name: 'Ranking', component: () => import('@/views/home/RankingView.vue'), meta: { title: '积分排行榜' } },
]

// 2.2 题库与作业模块
const problemRoutes = [
  { path: 'problem', name: 'Problem', component: () => import('@/views/problem/ProblemSetView.vue'), meta: { title: '题库' } },
  { path: 'problem/:id', name: 'ProblemDetail', component: () => import('@/views/problem/ProblemDetailView.vue'), meta: { title: '题目详情', breadcrumb: [{ label: '题库', path: '/problem' }] } },
  { path: 'problem/:id/solutions', name: 'ProblemSolutions', component: () => import('@/views/problem/ProblemSolutionsView.vue'), meta: { title: '题解', breadcrumb: [{ label: '题库', path: '/problem' }] } },
  { path: 'problem/list', name: 'ProblemList', component: () => import('@/views/problem-list/ProblemListView.vue'), meta: { title: '题单广场' } },
  { path: 'problem/list/:id', name: 'ProblemListDetail', component: () => import('@/views/problem-list/ProblemListDetailView.vue'), meta: { title: '题单详情', breadcrumb: [{ label: '题单广场', path: '/problem/list' }] } },
  { path: 'problem/list/:id/edit', name: 'ProblemListEdit', component: () => import('@/views/problem-list/ProblemListEditView.vue'), meta: { title: '编辑题单', breadcrumb: [{ label: '题单广场', path: '/problem/list' }], requiresAuth: true }},
  { path: 'submission', name: 'Submission', component: () => import('@/views/submission/SubmissionView.vue'), meta: { title: '提交记录' } },
  { path: 'submission/:id', name: 'SubmissionDetail', component: () => import('@/views/submission/SubmissionDetailView.vue'), meta: { title: '评测详情', breadcrumb: [{ label: '提交记录', path: '/submission' }] } },
]

// 2.2b 训练试卷（独立于题库/题单/课程）
const trainingRoutes = [
  { path: 'training', name: 'Training', component: () => import('@/views/training/TrainingView.vue'), meta: { title: '训练' } },
  { path: 'training/:id', name: 'TrainingDetail', component: () => import('@/views/training/ExamPaperDetailView.vue'), meta: { title: '试卷详情', breadcrumb: [{ label: '训练', path: '/training' }] } },
]

// 2.3 社区与文章模块
const communityRoutes = [
  { path: 'post', name: 'Post', component: () => import('@/views/post/PostView.vue'), meta: { title: '动态' } },
  { path: 'discussion', name: 'Discussion', component: () => import('@/views/post/DiscussionView.vue'), meta: { title: '讨论广场' } },
  { path: 'discussion/create', name: 'DiscussionCreate', component: () => import('@/views/post/PostEditorView.vue'), meta: { title: '发起讨论', breadcrumb: [{ label: '讨论广场', path: '/discussion' }], requiresAuth: true } },
  { path: 'post/:id', name: 'PostDetail', component: () => import('@/views/post/PostDetailView.vue'), meta: { title: '动态详情', breadcrumb: [{ label: '动态', path: '/post' }] } },
  { path: 'post/editor', name: 'PostCreate', component: () => import('@/views/post/PostEditorView.vue'), meta: { title: '写文章', breadcrumb: [{ label: '动态', path: '/post' }], requiresAuth: true } },
  { path: 'post/editor/:id', name: 'PostEdit', component: () => import('@/views/post/PostEditorView.vue'), meta: { title: '编辑文章', breadcrumb: [{ label: '动态', path: '/post' }], requiresAuth: true } },
  { path: 'announcement', name: 'Announcement', component: () => import('@/views/post/AnnouncementView.vue'), meta: { title: '系统公告' } },
]

// 2.4 比赛模块
const contestRoutes = [
  { path: 'contest', name: 'Contest', component: () => import('@/views/contest/ContestView.vue'), meta: { title: '比赛大厅' } },
  {
    path: 'contest/:id', component: () => import('@/layout/ContestLayout.vue'), meta: { title: '比赛详情', breadcrumb: [{ label: '比赛大厅', path: '/contest' }] },
    children: [
      { path: '', redirect: { name: 'ContestProblem' } },
      { path: 'problem', name: 'ContestProblem', component: () => import('@/views/contest/ContestProblemSetView.vue') },
      { path: 'submission', name: 'ContestSubmission', component: () => import('@/views/contest/ContestSubmissionView.vue') },
      { path: 'submit', name: 'ContestSubmit', redirect: { name: 'ContestProblem' } },
      { path: 'ranking', name: 'ContestRanking', component: () => import('@/views/contest/ContestRankingView.vue') },
      { path: 'editorial', name: 'ContestEditorial', component: () => import('@/views/contest/ContestEditorialView.vue') }
    ]
  }
]

// 2.5 班级与教学模块 (学生端 + 教师端)
const educationRoutes = [
  { path: 'my/class', name: 'MyClass', component: () => import('@/views/class/MyClassView.vue'), meta: { title: '我的课程', requiresAuth: true } },
  { path: 'my/homework', name: 'MyHomework', component: () => import('@/views/class/MyHomeworkView.vue'), meta: { title: '我的作业', requiresAuth: true } },
  {
    path: 'class/:id', component: () => import('@/views/class/ClassDetailView.vue'), meta: { title: '班级详情', breadcrumb: [{ label: '我的课程', path: '/my/class' }], requiresAuth: true },
    children: [
      { path: '', name: 'ClassDirectory', component: () => import('@/views/class/ClassDirectoryView.vue') },
      { path: 'homework', name: 'ClassHomework', component: () => import('@/views/class/ClassHomeworkView.vue') },
      { path: 'problem/list', name: 'ClassProblemList', component: () => import('@/views/class/ClassProblemListView.vue') },
      { path: 'contest', name: 'ClassContest', component: () => import('@/views/class/ClassContestView.vue') },
      { path: 'post', name: 'ClassPost', component: () => import('@/views/class/ClassPostView.vue') }
    ]
  },
]

// 2.6 用户个人中心模块
const userRoutes = [
  { path: '/my/post', name: 'MyPost', component: () => import('@/views/post/MyPostView.vue'), meta: { title: '我的动态', requiresAuth: true } },
  { path: '/my/problem/list', name:'MyProblemList', component: () => import('@/views/problem-list/MyProblemListView.vue'), meta: { title: '我的题单', requiresAuth: true } },
  { path: '/my/favorite', name: 'MyFavorite', component: () => import('@/views/user/FavoriteView.vue'), meta: { title: '我的收藏', requiresAuth: true } },
  { path: '/my/review', name: 'ReviewBook', component: () => import('@/views/user/ReviewBookView.vue'), meta: { title: '错题本', requiresAuth: true } },
  { path: '/my/notifications', name: 'NotificationCenter', component: () => import('@/views/user/NotificationCenterView.vue'), meta: { title: '通知中心', requiresAuth: true } },
  { path: '/user/setting', name: 'UserSetting', component: () => import('@/views/user/SettingView.vue'), meta: { title: '用户设置', requiresAuth: true } },
  { path: '/user/practice', name: 'Practice', component: () => import('@/views/user/PracticeView.vue'), meta: { title: '练习情况', requiresAuth: true } },
  {
    path: '/user/:id',
    component: () => import('@/layout/ProfileLayout.vue'),
    children: [
      { path: '', name: 'UserProfile', component: () => import('@/views/user/ProfileView.vue'), meta: { title: '个人中心' } },
      { path: 'relation', name: 'UserRelation', component: () => import('@/views/user/RelationView.vue'), meta: { title: '关注/粉丝' } }
    ]
  }
]

// 2.7 管理中心（AdminLayout 分组导航，URL 与原先一致 /admin/*）
const adminLayoutRoutes = [
  {
    path: '/admin',
    component: () => import('@/layout/AdminLayout.vue'),
    meta: { title: '管理中心', requiresAuth: true },
    children: [
      { path: '', name: 'AdminHome', component: () => import('@/views/admin/AdminHomeView.vue'), meta: { title: '管理中心' } },
      { path: 'class', name: 'AdminClass', component: () => import('@/views/admin/class/AdminClassView.vue'), meta: { title: '班级管理' } },
      { path: 'class/:classId', name: 'AdminClassDetail', component: () => import('@/views/admin/class/AdminClassDetailView.vue'), meta: { title: '课程管理' } },
      { path: 'class/:classId/homework/:homeworkId/status', name: 'AdminClassHomeworkStatus', component: () => import('@/views/admin/class/ClassHomeworkStatusPage.vue'), meta: { title: '作业完成情况' } },
      { path: 'courseware', name: 'AdminCourseware', component: () => import('@/views/admin/AdminCoursewareView.vue'), meta: { title: '课件管理' } },
      { path: 'problem', name: 'AdminProblem', component: () => import('@/views/admin/AdminProblemView.vue'), meta: { title: '公共题库' } },
      { path: 'problem/edit', name: 'AdminProblemCreate', component: () => import('@/views/admin/AdminProblemEditView.vue'), meta: { title: '新建题目', requiresAuth: true } },
      { path: 'problem/edit/:id', name: 'AdminProblemEdit', component: () => import('@/views/admin/AdminProblemEditView.vue'), meta: { title: '编辑题目', requiresAuth: true } },
      { path: 'contest', name: 'AdminContest', component: () => import('@/views/admin/AdminContestView.vue'), meta: { title: '比赛管理', requiresAuth: true } },
      { path: 'contest/create', name: 'AdminContestCreate', component: () => import('@/views/admin/ContestEditorView.vue'), meta: { title: '新建比赛', requiresAuth: true } },
      { path: 'contest/:id/edit', name: 'AdminContestEdit', component: () => import('@/views/admin/ContestEditorView.vue'), meta: { title: '编辑比赛', requiresAuth: true } },
      { path: 'dashboard', name: 'AdminDashboard', component: () => import('@/views/admin/AdminDashboardView.vue'), meta: { title: '运维仪表盘', requiresAuth: true } },
      { path: 'exam-paper', name: 'AdminExamPaper', component: () => import('@/views/admin/AdminExamPaperView.vue'), meta: { title: '训练试卷管理', requiresAuth: true } },
      { path: 'exam-paper/create', name: 'AdminExamPaperCreate', component: () => import('@/views/admin/AdminExamPaperEditorView.vue'), meta: { title: '新建试卷', requiresAuth: true } },
      { path: 'exam-paper/edit/:id', name: 'AdminExamPaperEdit', component: () => import('@/views/admin/AdminExamPaperEditorView.vue'), meta: { title: '编辑试卷', requiresAuth: true } },
      { path: 'ai', name: 'AdminAi', component: () => import('@/views/admin/AdminAiView.vue'), meta: { title: 'AI 管理', requiresAuth: true } },
      { path: 'users', name: 'AdminUsers', component: () => import('@/views/admin/AdminUsersView.vue'), meta: { title: '用户管理', requiresAuth: true } },
      { path: 'classes', name: 'AdminClasses', component: () => import('@/views/admin/AdminClassesView.vue'), meta: { title: '全部班级', requiresAuth: true } },
      { path: 'problem-lists', name: 'AdminProblemLists', component: () => import('@/views/admin/AdminProblemListsView.vue'), meta: { title: '官方题单管理', requiresAuth: true } },
      { path: 'announcements', name: 'AdminAnnouncements', component: () => import('@/views/admin/AdminAnnouncementsView.vue'), meta: { title: '公告管理', requiresAuth: true } },
      { path: 'audit-logs', name: 'AdminAuditLogs', component: () => import('@/views/admin/AdminAuditLogsView.vue'), meta: { title: '审计日志', requiresAuth: true } },
      { path: 'community', name: 'AdminCommunity', component: () => import('@/views/admin/AdminCommunityView.vue'), meta: { title: '社区治理', requiresAuth: true } },
      { path: 'community/reports/:id', name: 'AdminReportDetail', component: () => import('@/views/admin/AdminReportDetailView.vue'), meta: { title: '举报详情', requiresAuth: true } },
      { path: 'tools', name: 'AdminTools', component: () => import('@/views/admin/AdminToolsView.vue'), meta: { title: '站内工具', requiresAuth: true } },
      { path: 'tools/draft', name: 'AdminToolDraft', component: () => import('@/views/teacher/tools/DraftToolView.vue'), meta: { title: '题面生成', requiresAuth: true } },
      { path: 'plagiarism', name: 'AdminPlagiarism', component: () => import('@/views/teacher/PlagiarismReportView.vue'), meta: { title: '查重报告', requiresAuth: true } }
    ]
  }
]

// =============================================================================
// 3. 路由聚合
// =============================================================================
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),

  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    return { top: 0 }
  },

  routes: [
    ...authRoutes,
    ...studyRoutes,
    ...standaloneRoutes,
    ...adminLayoutRoutes,
    {
      path: '/',
      name: 'Layout',
      component: () => import('@/layout/BasicLayout.vue'),
      children: [
        ...coreRoutes,
        ...problemRoutes,
        ...trainingRoutes,
        ...communityRoutes,
        ...contestRoutes,
        ...educationRoutes,
        ...userRoutes
      ]
    },
    ...notFoundRoute,
  ]
})

// =============================================================================
// 4. 全局守卫 — 鉴权逻辑委托给 composable
// =============================================================================
// 记录进入时刻，供 afterEach 计算路由切换耗时（体验埋点用）
let routeStartTime = 0

router.beforeEach((to, from, next) => {
  routeStartTime = beginRouteTrack() // 体验埋点：路由切换计时

  // 仅做「是否登录」的粗粒度拦截，具体鉴权以各接口的后端校验为准——
  // 前端守卫只是体验优化（避免未登录用户看到空壳页面），不作为安全边界。
  if (to.meta.requiresAuth) {
    const userStore = useUserStore()

    if (!userStore.getAccessToken()) {
      // 中断本次导航后再弹确认框，redirect 参数带回原目标路径
      next(false)

      const { requireLogin } = useAuthGuard()
      requireLogin(router, to.fullPath)
      return
    }
  }

  next()
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} - 睿思学院` : '睿思学院'
  trackRoute(to, routeStartTime) // 体验埋点：耗时分布 + 慢切换(>500ms)
})

export default router
