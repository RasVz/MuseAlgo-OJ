<template>
  <NavbarContext>
    <div class="navbar-console">
      <div class="search-group">
        <span class="search-label">查找题单</span>
        <div class="custom-search-box">
          <input 
            :value="searchKey" 
            @input="$emit('update:searchKey', $event.target.value)"
            type="text" 
            placeholder="请输入关键词..." 
            @keyup.enter="$emit('search')"
          />
          <button @click="$emit('search')">搜索</button>
        </div>
      </div>
      <div class="spacer"></div>
      <el-radio-group :model-value="viewMode" @update:model-value="$emit('update:viewMode', $event)">
        <el-radio-button label="official">官方精选</el-radio-button>
        <el-radio-button label="shared">用户分享</el-radio-button>
      </el-radio-group>
    </div>
  </NavbarContext>
</template>

<script setup>
// 题单广场筛选栏：搜索关键词与视图切换（官方 / 共享），纯受控的展示组件。
import NavbarContext from '@/components/NavbarContext.vue'

defineProps(['searchKey', 'viewMode'])
defineEmits(['update:searchKey', 'update:viewMode', 'search'])
</script>

<style scoped>
.navbar-console { display: flex; align-items: center; width: 100%; }
.search-group { display: flex; align-items: center; gap: 15px; }
.search-label { font-size: 20px; font-weight: bold; color: var(--text-primary); }
.custom-search-box { 
  display: flex; align-items: center; border: 1px solid var(--border-base); 
  border-radius: 4px; height: 36px; overflow: hidden; 
}
.custom-search-box input { border: none; outline: none; padding: 0 12px; width: 240px; }
.custom-search-box button { background: var(--accent-primary); color: var(--text-on-accent); border: none; padding: 0 20px; height: 100%; cursor: pointer; font-weight: bold; }
.spacer { margin-left: 40px; }
</style>