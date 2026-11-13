<template>
  <div class="dashboard-content">
    <NavbarContext>
      <div style="font-size: 18px; font-weight: bold; color: var(--text-primary);">
        睿思学院
      </div>
    </NavbarContext>

    <div class="dashboard-grid">
      <section class="left-col" aria-label="我的班级">
        <MyClassList />
      </section>
      <aside class="right-col" aria-label="学习提醒与推荐">
        <div class="sidebar-item">
          <PendingHomework />
        </div>
        <div class="sidebar-item">
          <SmartRecommendations />
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
/**
 * 我的班级页：左侧班级列表，右侧两个侧栏卡片（待完成作业计数 + 题目推荐）。
 *
 * 本组件只负责布局与子组件编排，不持有任何业务状态、不发请求：
 * 三个子组件各自独立取数（MyClassList 取班级列表、PendingHomework 取待办计数、
 * SmartRecommendations 取推荐题目），互不依赖。
 *
 * 这么拆分的理由是三个数据源的刷新时机不同：
 * 待办计数要在交完作业后尽快更新、推荐列表只在进入时取一次、班级列表还要支持切换班级，
 * 收在一个组件里会导致任何一个变化都要重拉全部。
 *
 * 桌面右栏限制宽度，为班级列表保留空间；窄屏改为纵向排列。
 * 桌面两栏各自滚动，避免较矮窗口裁掉推荐题目或班级列表。
 */
import NavbarContext from '@/components/NavbarContext.vue'
import MyClassList from '@/components/MyClassList.vue'
import PendingHomework from '@/components/PendingHomework.vue'
import SmartRecommendations from '@/components/SmartRecommendations.vue'
</script>

<style scoped>
.dashboard-content {
  font-family: var(--font-family);
  height: calc(100vh - var(--app-header-height) - 40px);
  overflow: hidden;
}
.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(288px, 22vw, 320px);
  gap: 20px;
  height: 100%;
}
.left-col, .right-col {
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.right-col {
  overflow-y: auto;
  gap: 20px;
}
.sidebar-item {
  flex-shrink: 0;
}
@media (max-width: 1050px) {
  .dashboard-content { height: auto; overflow: visible; }
  .dashboard-grid { grid-template-columns: minmax(0, 1fr); height: auto; }
  .left-col, .right-col { height: auto; }
  .right-col { overflow: visible; }
}
</style>
