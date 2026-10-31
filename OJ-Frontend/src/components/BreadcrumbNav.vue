<template>
  <el-breadcrumb separator="/" class="crumb-nav">
    <el-breadcrumb-item
      v-for="(item, i) in crumbs"
      :key="i"
      :to="item.path && i < crumbs.length - 1 ? { path: item.path } : undefined"
    >
      {{ item.label }}
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

/**
 * 统一面包屑：优先读 route.meta.breadcrumb（上级路径数组 [{label, path}]），
 * 末尾自动追加当前页 title；未配置时退化为显示当前页标题。
 */
const route = useRoute()

const crumbs = computed(() => {
  const breadcrumb = route.meta.breadcrumb || []
  const title = route.meta.title
  const list = breadcrumb.map((item) => ({ label: item.label, path: item.path }))
  if (title && !list.some((c) => c.label === title)) {
    list.push({ label: title, path: '' })
  }
  if (list.length === 0) {
    list.push({ label: title || '睿思学院', path: '' })
  }
  return list
})
</script>

<style scoped>
.crumb-nav {
  font-size: 15px;
  font-weight: 600;
}
.crumb-nav :deep(.el-breadcrumb__inner) {
  color: var(--text-primary, #1f2328);
}
.crumb-nav :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--text-regular, #656d76);
  font-weight: 500;
}
.crumb-nav :deep(.el-breadcrumb__separator) {
  color: var(--text-secondary, #8b949e);
  font-weight: 400;
}
</style>
