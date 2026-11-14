<template>
  <div class="basic-layout">
    <el-container style="height: 100vh;">
      <SideBar />
      
      <el-container direction="vertical">
        <Navbar />
        
        <el-main class="main-content" :class="{ immersive: route.meta.immersive }">
          <router-view v-if="isMounted" v-slot="{ Component }">
            <transition name="page-fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref, onMounted, provide, markRaw } from 'vue'
import { useRoute } from 'vue-router'
import SideBar from '@/components/SideBar.vue'
import Navbar from '@/components/Navbar.vue'

const route = useRoute()
const isMounted = ref(false)

// provide 提升到 BasicLayout（Navbar 和 router-view 的共同祖先）
// 这样 NavbarContext 在任何子路由视图中都能 inject 到
const contextComp = ref(null)
provide('setNavbarContext', (comp) => {
  contextComp.value = comp ? markRaw(comp) : null
})
provide('navbarContext', contextComp)

onMounted(() => {
  isMounted.value = true
})
</script>

<style scoped>
.main-content { 
    background-color: var(--bg-app); 
    padding: 20px; 
}
/* 整卷答题等沉浸式页面：去掉页面内边距并锁住滚动，由页面自己管理高度。 */
.main-content.immersive {
    padding: 0;
    overflow: hidden;
}

/* 路由切换轻量淡入淡出（150ms，mode=out-in 避免重叠错位） */
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.15s ease;
}
.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}
</style>
