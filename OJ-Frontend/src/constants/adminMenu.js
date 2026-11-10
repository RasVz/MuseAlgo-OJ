import {
  FolderOpened, List, DataBoard, Bell, School, Notebook, EditPen, DocumentChecked,
  Trophy, Collection, Medal, RefreshRight, UserFilled, Flag, MagicStick, Connection,
  Document, Odometer
} from '@element-plus/icons-vue'

// 管理中心导航唯一数据源：侧栏与首页共用，minRole 缺省为教师（2）。
export const ADMIN_MENU_GROUPS = [
  {
    title: '内容与资源',
    items: [
      { label: '题库管理', desc: '题目 CRUD / FPS 导入', path: '/admin/problem', icon: FolderOpened },
      { label: '官方题单', desc: '维护题单广场', path: '/admin/problem-lists', icon: List },
      { label: '课件资源', desc: 'Slidev 课件', path: '/admin/courseware', icon: DataBoard },
      { label: '公告管理', desc: '发布 / 置顶公告', path: '/admin/announcements', icon: Bell }
    ]
  },
  {
    title: '班级与学情',
    items: [
      { label: '班级管理', desc: '班级 / 作业完成情况', path: '/admin/class', icon: School },
      { label: '学习周报', desc: '生成 / 历史 / HTML / PNG', path: '/admin/reports', icon: Notebook },
      { label: '全部班级', desc: '查看所有教师创建的班级', path: '/admin/classes', icon: School, minRole: 3 }
    ]
  },
  {
    title: '教学工具',
    items: [
      { label: '题面生成', desc: '输入命题要求，AI 生成完整题面并保存为题目', path: '/admin/tools/draft', icon: EditPen },
      { label: '查重报告', desc: '班级 / 比赛内同题提交两两比对，识别疑似抄袭', path: '/admin/plagiarism', icon: DocumentChecked }
    ]
  },
  {
    title: '比赛与训练',
    items: [
      { label: '比赛管理', desc: '创建 / 成绩导出', path: '/admin/contest', icon: Trophy },
      { label: '训练试卷', desc: '套卷出题 / 成绩统计', path: '/admin/exam-paper', icon: Collection, minRole: 3 },
      { label: '比赛等级', desc: '等级 / Rating 范围', path: '/admin/contest-levels', icon: Medal, minRole: 3 },
      { label: '受控重判', desc: '单提交 / 范围批量 / 操作记录', path: '/admin/rejudge', icon: RefreshRight, minRole: 3 }
    ]
  },
  {
    title: '用户与社区',
    items: [
      { label: '用户管理', desc: '角色 / 禁用', path: '/admin/users', icon: UserFilled, minRole: 3 },
      { label: '社区治理', desc: '帖子 / 评论治理', path: '/admin/community', icon: Flag, minRole: 3 }
    ]
  },
  {
    title: 'AI 与系统',
    items: [
      { label: 'AI 管理', desc: '用量 / 配置 / 模板 / 配额', path: '/admin/ai', icon: MagicStick, minRole: 3 },
      { label: 'AI 服务', desc: '接口 / 模型 / 密钥 / 全站切换', path: '/admin/ai-services', icon: Connection, minRole: 3 },
      { label: '报告通知队列', desc: '周报提醒投递状态 / 重投', path: '/admin/report-notifications', icon: Bell, minRole: 3 },
      { label: '审计日志', desc: '操作审计查询', path: '/admin/audit-logs', icon: Document, minRole: 3 },
      { label: '运维仪表盘', desc: '统计与趋势', path: '/admin/dashboard', icon: Odometer, minRole: 3 }
    ]
  }
]

export function getAdminMenuGroups(role) {
  return ADMIN_MENU_GROUPS
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => role >= (item.minRole ?? 2))
    }))
    .filter((group) => group.items.length > 0)
}
