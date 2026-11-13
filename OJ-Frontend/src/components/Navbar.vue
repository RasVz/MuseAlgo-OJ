<template>
  <el-header class="app-navbar">
    <div class="context-container">
      <transition name="fade" mode="out-in">
        <component v-if="contextComp" :is="contextComp" />
        <BreadcrumbNav v-else />
      </transition>
    </div>

    <NavbarUser />
  </el-header>
</template>

<script setup>
import { inject } from 'vue'
import NavbarUser from '@/components/NavbarUser.vue'
import BreadcrumbNav from '@/components/BreadcrumbNav.vue'

// Navbar 从 BasicLayout inject 导航栏上下文内容
// （原先的 Teleport #navbar-context-target 改为 provide/inject，由 BasicLayout 提供）
const contextComp = inject('navbarContext', null)
</script>

<style scoped>
.app-navbar {
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-light);
  height: var(--app-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  box-sizing: border-box;
  z-index: 100;
}

.context-container {
  flex: 1;
  height: 100%;
  display: flex;
  align-items: center;
  margin-right: 20px;
  min-width: 0;
  /* 注入的筛选栏含固定宽度控件，窄屏下会远宽于可用空间。
     收口为可横向滚动，避免撑破顶栏、挤走右侧用户区。 */
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
}
.context-container::-webkit-scrollbar { display: none; }

@media (max-width: 600px) {
  .app-navbar { padding: 0 12px; }
  .context-container { margin-right: 10px; }
}

.fade-enter-active, .fade-leave-active { transition: opacity 0.3s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
