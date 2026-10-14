<template>
  <div class="problems-group">
    <div v-for="group in groupedProblems" :key="group.level" class="group-block">
      <el-tag :type="group.type" effect="dark" size="small" class="level-badge">{{ group.level }}</el-tag>
      <div class="p-links">
        <el-link 
          v-for="problem in group.items" 
          :key="problem" 
          :type="group.linkType" 
          :underline="false" 
          class="p-link"
          @click="$router.push({ name: 'ProblemDetail', params: { id: problem } })"
        >
          {{ problem }}
        </el-link>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 按难度分组展示题目编号列表（题单的「已通过题目」形态）。
 *
 * 分组完全由调用方完成——本组件不接收平铺的题目数组，也不做分桶或排序，
 * 而是直接消费已分好组的结构化数据。原因是分组依据在不同页面并不一致：
 * 练习页按难度档分、班级作业可能按知识点或考试场次分，
 * 把某一种分桶逻辑写进组件会让另一处用法被迫绕开它。
 *
 * 契约：groupedProblems 形如
 * [{ level: '普及-', type: 'warning', linkType: 'warning', items: ['P1001', 'CF4A'] }]，其中
 * - level：分组标题（难度名或知识点名），同时作为 key；
 * - type：分组标签配色，linkType：组内链接配色，两者分开是因为标题与链接的语义色可以不同；
 * - items：题目编号字符串数组（不是对象，也不是纯数字——含 CF/AT 等非纯数字前缀的题号）。
 *
 * 分组顺序与组内顺序均以数组顺序为准，组件不排序：
 * 排序规则（按难度递增、按通过时间倒序）属于业务口径，应由数据提供方决定。
 */
defineProps(['groupedProblems'])
</script>

<style scoped>
.group-block { margin-bottom: 20px; }
.level-badge { margin-bottom: 10px; }
.p-links { display: flex; flex-wrap: wrap; gap: 10px; }
.p-link { font-family: 'Menlo', monospace; font-size: 14px; }
</style>