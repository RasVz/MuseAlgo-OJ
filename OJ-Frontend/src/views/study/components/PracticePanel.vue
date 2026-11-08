<template>
  <aside class="sidebar-practice" :class="{ open }">
    <div class="practice-header">
      <span class="ph-title"><el-icon><EditPen /></el-icon> 课后练习</span>
      <el-button link :icon="Close" @click="$emit('close')" />
    </div>

    <div class="practice-body">
      <div v-if="problemList.length > 0">
        <div
          v-for="(problem, index) in problemList"
          :key="problem.id || problem.problemId || index"
          class="problem-card"
          @click="$emit('open-problem', problem)"
        >
          <div class="pc-head">
            <span class="pc-index">{{ index + 1 }}.</span>
            <span class="pc-title">{{ problem.title }}</span>
          </div>
          <div class="pc-tags">
            <el-tag size="small" type="info">{{ problem.difficulty || '简单' }}</el-tag>
            <el-tag size="small" v-if="problem.status === 'Solved'" type="success">已通过</el-tag>
          </div>
        </div>
      </div>
      <el-empty v-else description="本节暂无关联练习" :image-size="80" />
    </div>
  </aside>
</template>

<script setup>
/**
 * 学习页右侧的「课后练习」抽屉：从右侧滑出，列出当前课件关联题单里的题目。
 *
 * 纯展示组件：题目列表由父组件（课程学习页）按当前课件的 problemListId 拉取后传入，
 * 组件不请求也不缓存——切换课件时列表整体替换，若在组件内缓存会短暂显示上一个课件的题目。
 *
 * 契约：
 * - open：控制展开动画，只影响 width 从 0 到 320px 的过渡，不销毁内容，
 *   这样收起再展开时用户不会看到列表重新加载的闪烁；
 * - problemList：父组件已归一化后的题目数组，
 *   元素 id 字段可能是 id 也可能是 problemId（两种来源的历史差异），
 *   因此 key 与后续跳转都要兼容两者——缺 id 时退化为索引下标，保证列表至少能渲染出来。
 *
 * 空态是必需的：本节课件没配题单属于常态（多数课件只有讲义），
 * 此时显示「本节暂无关联练习」比留一块空白更能解释面板为什么是空的。
 */
import { EditPen, Close } from '@element-plus/icons-vue'

defineProps({
  open: { type: Boolean, default: false },
  // 默认空数组而非 null：父组件异步赋值前模板就会求值，null 会让 v-for 直接抛错
  problemList: { type: Array, default: () => [] }
})
// close 与 open-problem 都不在这里处理，只负责向上抛：
// 「打开题目」涉及新开标签页这类浏览器行为，放在学习页统一处理更便于集中管控
defineEmits(['close', 'open-problem'])
</script>

<style scoped>
.sidebar-practice {
  width: 0;
  background: var(--bg-surface);
  border-left: 1px solid var(--border-base);
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.sidebar-practice.open { width: 320px; }

.practice-header {
  height: 45px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 15px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-input);
}
.ph-title { font-weight: 600; color: var(--text-primary); display: flex; align-items: center; gap: 6px; }

.practice-body { flex: 1; padding: 15px; overflow-y: auto; }

.problem-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}
.problem-card:hover { border-color: var(--accent-primary); transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
.pc-head { display: flex; gap: 8px; margin-bottom: 8px; font-weight: 500; color: var(--text-primary); }
.pc-tags { display: flex; gap: 6px; }
</style>
