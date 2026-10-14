<template>
  <main class="notifications" v-loading="loading">
    <NavbarContext><div class="navbar-title"><el-icon><Bell /></el-icon><span>通知中心</span></div></NavbarContext>
    <header>
      <div><span class="eyebrow">INBOX</span><h1>通知中心</h1><p>互动、关注动态，以及即将开始的比赛和临近截止的作业都会集中在这里。</p></div>
      <el-button :disabled="!items.some(item => !item.isRead && Number(item.id) > 0)" @click="readAll">全部标为已读</el-button>
    </header>
    <div class="tabs"><button v-for="tab in tabs" :key="tab.value" :class="{ active: scope === tab.value }" @click="switchScope(tab.value)">{{ tab.label }}</button></div>
    <section v-if="items.length" class="notification-list">
      <button v-for="item in items" :key="`${item.type}-${item.id}`" class="notification" :class="{ unread: !item.isRead && Number(item.id) > 0 }" @click="open(item)">
        <span class="notice-icon" :class="item.type"><el-icon><component :is="iconFor(item.type)" /></el-icon></span>
        <span class="notice-content"><span class="notice-title">{{ item.title }}</span><span class="notice-text">{{ item.content || '点击查看详情' }}</span><span class="notice-time">{{ formatTime(item.createTime) }}</span></span>
        <span v-if="!item.isRead && Number(item.id) > 0" class="unread-dot" />
      </button>
    </section>
    <el-empty v-else-if="!loading" description="暂时没有新通知" :image-size="96" />
  </main>
</template>

<script setup>
// 通知中心：按「全部 / 互动 / 今日任务」三个范围查看通知并标记已读。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, ChatDotRound, CollectionTag, Flag, Reading, Star, Timer, Trophy } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import NavbarContext from '@/components/NavbarContext.vue'
import { getNotifications, markAllNotificationsRead, markNotificationRead } from '@/api/notification'

const router = useRouter()
const loading = ref(false), scope = ref('all'), items = ref([])
const tabs = [{ value: 'all', label: '全部' }, { value: 'interactions', label: '互动' }, { value: 'today', label: '今日任务' }]
// 通知类型 → 图标的映射。未知类型兜底为通用铃铛：
// 后端新增通知类型时前端不会因为缺图标而渲染空白。
const iconFor = (type) => ({ LIKE: Star, FAVORITE: CollectionTag, COMMENT: ChatDotRound, REPLY: ChatDotRound, FOLLOW_POST: Reading, CONTEST_REMINDER: Trophy, HOMEWORK_REMINDER: Timer, MODERATION: Flag }[type] || Bell)
const formatTime = (time) => time ? dayjs(time).format('MM-DD HH:mm') : '刚刚'
const load = async () => { loading.value = true; try { const res = await getNotifications(scope.value); items.value = res.code === 200 ? res.data || [] : [] } finally { loading.value = false } }
const switchScope = (value) => { if (scope.value !== value) { scope.value = value; load() } }
// 先标记已读再跳转：读状态是"看过"的唯一依据，若跳转失败也不该让它一直是未读。
const open = async (item) => {
  if (!item.isRead && Number(item.id) > 0) { await markNotificationRead(item.id); item.isRead = 1 }
  if (item.targetType === 'post') router.push({ name: 'PostDetail', params: { id: item.targetId } })
  if (item.targetType === 'problem') router.push({ name: 'ProblemDetail', params: { id: item.targetId } })
  if (item.targetType === 'contest') router.push({ name: 'ContestProblem', params: { id: item.targetId } })
  if (item.targetType === 'homework') router.push({ name: 'MyHomework' })
}
// 全部已读后本地批量置位，不重拉列表：服务端只是一次批量更新，
// 重拉会让顶栏的未读数与列表短暂不一致。
const readAll = async () => { const res = await markAllNotificationsRead(); if (res.code === 200) items.value.forEach(item => { if (Number(item.id) > 0) item.isRead = 1 }) }
onMounted(load)
</script>

<style scoped>
.notifications { max-width: 900px; margin: 0 auto; padding: 28px 24px 52px; }.navbar-title { display:flex; align-items:center; gap:8px; font-size:18px; font-weight:650; color:var(--text-primary); }header { display:flex; justify-content:space-between; align-items:flex-start; gap:20px; margin-bottom:22px; }.eyebrow { color:var(--accent-primary); font-size:10px; font-weight:750; letter-spacing:.13em; }h1 { margin:5px 0 0; font-size:26px; color:var(--text-primary); }p { margin:8px 0 0; color:var(--text-regular); font-size:14px; }.tabs { display:flex; gap:5px; border-bottom:1px solid var(--border-light); }.tabs button { padding:10px 14px; border:0; border-bottom:2px solid transparent; background:transparent; color:var(--text-regular); cursor:pointer; }.tabs button.active { border-color:var(--accent-primary); color:var(--accent-primary); font-weight:650; }.notification-list { margin-top:6px; }.notification { position:relative; display:flex; width:100%; gap:13px; padding:15px 18px; border:0; border-bottom:1px solid var(--border-light); background:transparent; color:inherit; text-align:left; cursor:pointer; }.notification:hover { background:var(--bg-surface-hover); }.notification.unread { background:var(--accent-primary-light); }.notice-icon { display:grid; width:34px; height:34px; place-items:center; flex:0 0 34px; border-radius:10px; background:var(--accent-primary-light); color:var(--accent-primary); }.notice-icon.LIKE,.notice-icon.FAVORITE { background:var(--accent-danger-light); color:var(--accent-danger); }.notice-icon.HOMEWORK_REMINDER,.notice-icon.CONTEST_REMINDER { background:var(--accent-warning-light); color:var(--accent-warning); }.notice-content { display:grid; min-width:0; gap:3px; }.notice-title { color:var(--text-primary); font-size:14px; font-weight:600; }.notice-text { overflow:hidden; color:var(--text-regular); font-size:13px; text-overflow:ellipsis; white-space:nowrap; }.notice-time { color:var(--text-secondary); font-size:12px; }.unread-dot { position:absolute; top:20px; right:10px; width:7px; height:7px; border-radius:50%; background:var(--accent-primary); }@media(max-width:580px) { .notifications{padding:20px 14px 40px} header p{display:none} }
</style>
