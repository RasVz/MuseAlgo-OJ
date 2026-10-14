<template>
  <aside class="sidebar-directory" :class="{ collapsed: collapsed }">
    <div class="sidebar-header">
      <span v-if="!collapsed">课程目录</span>
      <el-icon class="toggle-btn" @click="$emit('update:collapsed', !collapsed)">
        <component :is="collapsed ? Expand : Fold" />
      </el-icon>
    </div>

    <el-scrollbar v-if="!collapsed">
      <el-tree
        ref="treeRef"
        :props="{ label: 'title', children: 'children' }"
        :data="tree"
        node-key="id"
        :current-node-key="currentId"
        default-expand-all
        highlight-current
        @node-click="$emit('node-click', $event)"
      >
        <template #default="{ node, data }">
          <div class="custom-tree-node">
            <el-icon v-if="data.type === 'chapter'"><Folder /></el-icon>
            <el-icon v-else><Document /></el-icon>
            <span class="node-label">{{ node.label }}</span>
            <el-tag
              v-if="isCurrent(data.id)"
              size="small"
              type="success"
              effect="dark"
            >
              当前
            </el-tag>
          </div>
        </template>
      </el-tree>
    </el-scrollbar>
  </aside>
</template>

<script setup>
/**
 * 学习页左侧的课程目录树（章节 + 课件两级）。
 *
 * 本组件是纯受控展示组件：自己不请求数据，只消费父组件（课程学习页）传来的目录树，
 * 并把交互意图抛回去。它解决的核心问题是「选中态要同时反映在两个方向上」——
 * 用户点击节点时高亮要立刻跟着动（由父组件更新 currentId 回传），
 * 而父组件在初始化/加载新课件后也要能主动把高亮打到指定节点
 * （由 setCurrentKey 命令式方法完成）。见下文 defineExpose 处的说明。
 *
 * 契约：
 * - tree：形如 [{ id, title, type: 'chapter' | 'file', children: [] }]，直接来自班级目录接口；
 * - currentId：当前课件 ID，可能被路由参数传成字符串，所以比较前统一归一化（见 normalizeId）；
 * - v-model:collapsed：折叠态由父组件持有，组件只负责在点击时抛出 update:collapsed。
 *
 * 注意 el-tree 的 props 把 label 映射到 title、children 映射到 children，
 * 说明后端目录节点的字段名不是 Element Plus 默认的 label。
 */
import { ref } from 'vue'
import { Expand, Fold, Folder, Document } from '@element-plus/icons-vue'

const props = defineProps({
  collapsed: { type: Boolean, default: false },
  tree: { type: Array, default: () => [] },
  // 同时接受 Number 与 String：路由参数拿到的 coursewareId 是字符串，
  // 而目录接口返回的 id 是数字，不声明联合类型会在开发期告警、运行期则比较不中。
  currentId: { type: [Number, String], default: null }
})
defineEmits(['update:collapsed', 'node-click'])

const treeRef = ref(null)

// null/undefined 归一化为空字符串，这样「未选中」时与任何脏值都不会误判为相等。
// 统一用 String 比较是为了让 1 === '1' 成立，消掉数字/字符串 ID 混用带来的高亮丢失。
const normalizeId = (val) => (val == null ? '' : String(val))
const isCurrent = (id) => normalizeId(id) === normalizeId(props.currentId)

/**
 * 暴露给父组件的命令式方法：主动把高亮打到指定节点。
 *
 * 存在的理由是 el-tree 的 current-node-key 是单向的——
 * 它只在组件挂载/数据变化时生效，运行期外部改 currentId 不会让已渲染的树跟着移动高亮，
 * 且目录数据是异步加载的，高亮节点可能还没被创建。
 * 因此父组件必须在拿到目录数据后的 nextTick 里调用本方法。
 * 用可选链串联（?.）是因为父组件 ref 可能还没挂上或已被卸载，调用不应抛错。
 */
defineExpose({
  setCurrentKey: (key) => treeRef.value?.setCurrentKey?.(key)
})
</script>

<style scoped>
.sidebar-directory {
  width: 260px;
  background: var(--bg-surface);
  border-right: 1px solid var(--border-base);
  display: flex;
  flex-direction: column;
  transition: width 0.3s ease;
}
.sidebar-directory.collapsed { width: 50px; }

.sidebar-header {
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 15px;
  border-bottom: 1px solid var(--el-fill-color-light);
  color: var(--text-secondary);
  font-size: 13px;
}
.toggle-btn { cursor: pointer; font-size: 16px; }
.sidebar-directory.collapsed .sidebar-header { justify-content: center; }

.custom-tree-node { display: flex; align-items: center; gap: 6px; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; width: 100%; }
.node-label { flex: 1; overflow: hidden; text-overflow: ellipsis; }
</style>
