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
      <el-radio-group class="view-switch" :model-value="viewMode" @update:model-value="$emit('update:viewMode', $event)">
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
.search-label { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.custom-search-box { 
  display: flex; align-items: center; border: 1px solid var(--border-base); 
  border-radius: var(--radius-control); height: var(--control-height); overflow: hidden; 
  background: var(--bg-surface);
}
.custom-search-box input {
  border: none; outline: none; padding: 0 12px; width: 240px;
  background: transparent; color: var(--text-primary); font-size: 14px;
}
.custom-search-box input::placeholder { color: var(--text-placeholder); }
.custom-search-box button { background: var(--accent-primary); color: var(--text-on-accent); border: none; padding: 0 20px; height: 100%; cursor: pointer; font-weight: 500; font-size: 14px; }
.spacer { margin-left: 40px; }

/* 视图切换按页签处理：只保留文字与下划线激活态 */
.view-switch :deep(.el-radio-button__inner) {
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  box-shadow: none !important;
  padding: 0 2px 6px;
  color: var(--text-regular);
  font-size: 14px;
  font-weight: 500;
}
.view-switch :deep(.el-radio-button + .el-radio-button) { margin-left: 20px; }
.view-switch :deep(.el-radio-button__inner:hover) { color: var(--accent-primary); }
.view-switch :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: transparent;
  border-bottom-color: var(--accent-primary);
  box-shadow: none !important;
  color: var(--text-primary);
  font-weight: 600;
}
</style>