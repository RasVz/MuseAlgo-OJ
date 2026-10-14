<template>
  <div class="basic-layout">
    <el-container style="height: 100vh;">
      <SideBar />
      
      <el-container direction="vertical">
        <Navbar />
        
        <el-main class="main-content">
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
import SideBar from '@/components/SideBar.vue'
import Navbar from '@/components/Navbar.vue'

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
