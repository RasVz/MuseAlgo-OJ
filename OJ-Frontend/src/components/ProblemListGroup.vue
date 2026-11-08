<template>
  <div class="problems-group">
    <div v-for="group in groupedProblems" :key="group.level" class="group-block">
      <el-tag :type="group.type" effect="dark" size="small" class="level-badge">{{ group.level }}</el-tag>
      <div class="p-links">
        <el-link
          v-for="(problem, index) in group.items"
          :key="itemKey(problem, index)"
          :type="group.linkType"
          :underline="false"
          :class="isStructuredItem(problem) ? 'p-link p-link-title' : 'p-link p-link-code'"
          @click="$router.push({ name: 'ProblemDetail', params: { id: itemId(problem) } })"
        >
          {{ itemLabel(problem) }}
        </el-link>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 按难度分组展示题目列表（练习分析页的「已通过 / 尝试过题目」形态）。
 *
 * 分组完全由调用方完成——本组件不接收平铺题目数组，也不做分桶或排序，
 * 只消费已分好组的结构化数据。原因是分组依据在不同页面并不一致：
 * 练习页按难度档分、班级作业可能按知识点或考试场次分。
 *
 * 契约：groupedProblems 形如
 * [{ level: '普及-', type: 'warning', linkType: 'warning', items: [...] }]，其中
 * - level：分组标题（难度名或知识点名），同时作为 key；
 * - type：分组标签配色，linkType：组内链接配色，两者分开是因为标题与链接的语义色可以不同；
 * - items 兼容两种元素：
 *   1) 字符串题号（历史用法，如 'P1001'，原样展示并按该值跳转）；
 *   2) { id, title } 结构（练习分析用真实数值 id 跳转并展示题目标题）。
 *
 * 分组顺序与组内顺序均以数组顺序为准，组件不排序：业务口径由数据提供方决定。
 */
defineProps(['groupedProblems'])

const isStructuredItem = (item) => item !== null && typeof item === 'object'
const itemId = (item) => (isStructuredItem(item) ? item.id : item)
const itemLabel = (item) => {
  if (!isStructuredItem(item)) return item
  return item.title || '#' + item.id
}
const itemKey = (item, index) => (isStructuredItem(item) ? (item.id ?? index) : item)
</script>

<style scoped>
.group-block { margin-bottom: 20px; }
.level-badge { margin-bottom: 10px; }
.p-links { display: flex; flex-wrap: wrap; gap: 10px; }
.p-link { font-size: 14px; }
.p-link-code { font-family: 'Menlo', monospace; }
.p-link-title { font-family: inherit; }
</style>
