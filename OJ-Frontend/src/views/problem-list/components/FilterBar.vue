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
      <WorkbenchSegmented
        :model-value="viewMode"
        :options="viewOptions"
        size="sm"
        aria-label="题单视图"
        @update:model-value="$emit('update:viewMode', $event)"
      />
    </div>
  </NavbarContext>
</template>

<script setup>
// 题单广场筛选栏：搜索关键词与视图切换（官方 / 共享），纯受控的展示组件。
import NavbarContext from '@/components/NavbarContext.vue'
import WorkbenchSegmented from '@/components/WorkbenchSegmented.vue'

defineProps(['searchKey', 'viewMode'])
defineEmits(['update:searchKey', 'update:viewMode', 'search'])

const viewOptions = [
  { value: 'official', label: '官方精选' },
  { value: 'shared', label: '用户分享' }
]
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

</style>