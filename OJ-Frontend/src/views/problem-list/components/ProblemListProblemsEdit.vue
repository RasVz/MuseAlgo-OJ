<template>
  <div class="problems-container">
    <div class="manage-tools">
      <div class="tool-label">快速添加</div>
      <el-input 
        v-model="newProblemId" 
        placeholder="输入题目 ID (例如 1001)" 
        class="add-input" 
        size="large" 
        @keyup.enter="addProblem"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
        <template #append>
          <el-button type="primary" @click="addProblem" class="add-btn">
            <el-icon style="margin-right: 4px"><Plus /></el-icon> 添加
          </el-button>
        </template>
      </el-input>
      <span class="tool-tip">提示：按住左侧图标可拖拽调整题目顺序</span>
    </div>

    <el-table 
      :data="problemList" 
      style="margin-top: 20px; border-radius: 8px; overflow: hidden;" 
      border 
      stripe 
      row-key="id" 
      ref="tableRef"
      :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)', fontWeight: 'bold' }"
    >
      <el-table-column width="60" align="center">
        <template #default>
          <el-icon class="drag-handler" style="cursor: move; font-size: 18px; color: var(--text-secondary);">
            <Rank />
          </el-icon>
        </template>
      </el-table-column>

      <el-table-column prop="displayId" label="题目ID" width="120" align="center">
        <template #default="{ row }"><span class="id-tag">{{ row.id }}</span></template>
      </el-table-column>
      
      <el-table-column prop="title" label="题目名称">
        <template #default="{ row }"><span class="problem-title">{{ row.title }}</span></template>
      </el-table-column>

      <el-table-column label="难度" width="100" align="center">
        <template #default="{ row }">
          <span class="diff-tag" :style="{ color: getDifficultyColor(row.difficulty) }">
            {{ getDiffLabel(row.difficulty) }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="120" align="center">
        <template #default="scope">
          <el-button 
            type="danger" 
            plain 
            size="small" 
            icon="Delete" 
            class="delete-btn" 
            @click="removeProblem(scope.row)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
// 题单题目管理：按题目 ID 添加、移除、拖拽排序。
// 顺序不是"逐条更新 sort"，而是每次变动后把完整 ID 数组整体提交，
// 这样后端只需一次写入即可重建顺序，也避免部分更新导致顺序出现空洞。
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, Rank } from '@element-plus/icons-vue'
import Sortable from 'sortablejs' 
import { 
  addProblemToList, 
  removeProblemFromList,
  getListProblems,
  updateProblemOrder // 确保引入了这个接口
} from '@/api/problemList'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'

const props = defineProps({
  listId: { type: String, required: true },
  active: Boolean 
})

const emit = defineEmits(['update-count'])

const problemList = ref([])
const newProblemId = ref('')
const tableRef = ref(null)

const getDiffLabel = getDifficultyLabel

// 顺序保存统一走这里。silent 用于添加题目后的自动排序：
// 那次排序是"添加"的附带步骤，再弹一个"顺序已更新"会让用户以为发生了两件事。
const saveCurrentOrder = async (silent = false) => {
  // 提交的是当前界面上的完整 ID 序列，后端按数组下标重建 sort（0,1,2...）。
  // 空列表直接跳过：没有意义的"提交空顺序"会让后端把整单顺序清掉。
  const sortedIds = problemList.value.map(item => item.id)
  if (sortedIds.length === 0) return

  try {
    // 2. 发送给后端，强制更新所有题目的 sort 值 (0, 1, 2...)
    await updateProblemOrder({
      listId: props.listId,
      problemIds: sortedIds
    })
    if (!silent) {
      ElMessage.success('顺序已更新')
    }
  } catch (error) {
    console.error('自动排序失败', error)
  }
}

const fetchProblems = async () => {
  try {
    const res = await getListProblems({ listId: props.listId })
    problemList.value = res.data || []
    emit('update-count', problemList.value.length)
    
    // 拉完题目必须重建 Sortable：绑定的是渲染后的 tbody 节点，
    // 数据换了之后旧实例仍持有旧行引用。
    nextTick(() => {
      initSortable()
    })
  } catch (error) {
    console.error(error)
  }
}

// 添加题目：新增的题默认排到末尾。
// 必须"先添加、再整体重排"两步走——添加接口不给新题分配有意义的 sort 值，
// 若只添加不重排，新题会随机插在中间，用户每次添加的顺序都不一样。
const addProblem = async () => {
  // 先把输入的 ID 存下来：清空输入框后就取不到了
  const targetId = newProblemId.value
  if (!targetId) return

  try {
    // 2. 调用后端接口添加
    await addProblemToList({ 
      problemDisplayId: targetId,
      problemListId: props.listId 
    })
    ElMessage.success('添加成功')
    newProblemId.value = '' // 清空输入框
    
    // 重新拉列表后新题可能排在任意位置（sort 值由后端分配）
    await fetchProblems()

    // 找到刚加的题并挪到末尾。id 与 displayId 两个字段都匹配：
    // 用户输入的是题号（displayId），而列表项的主键是关联记录 id，只匹配一个会找不到。
    const index = problemList.value.findIndex(p =>
      String(p.id) === String(targetId) || String(p.displayId) === String(targetId)
    )

    if (index !== -1) {
      const newItem = problemList.value.splice(index, 1)[0]
      problemList.value.push(newItem)
    }

    // 把"置底后"的顺序整体提交，新题的 sort 才真正变成最大值
    await saveCurrentOrder(true)
    
  } catch (error) {
    ElMessage.error(error.msg || '添加失败')
  }
}

// 移除后必须补一次整体排序：删掉中间一题会让后面的 sort 留下空洞，
// 后续拖拽再提交时顺序就可能错乱。silent 因为"移除成功"的提示已经给过了。
const removeProblem = (row) => {
  ElMessageBox.confirm(`确定要移除题目 ${row.title} 吗？`, '提示', {
    confirmButtonText: '移除',
    cancelButtonText: '取消',
    type: 'warning',
  }).then(async () => {
    try {
      await removeProblemFromList({ 
        problemId: row.id,
        problemListId: props.listId 
      })
      ElMessage.success('已移除')
      
      // 1. 刷新列表
      await fetchProblems()
      // 2. 重新排列剩下的题目序号 (填补空缺)
      await saveCurrentOrder(true)
      
    } catch {
      ElMessage.error('移除失败')
    }
  })
}

// Sortable 必须挂在 el-table 内部的 tbody 上，而不是外层容器
const initSortable = () => {
  const el = tableRef.value.$el.querySelector('.el-table__body-wrapper tbody')
  if (!el) return

  // 防止重复初始化
  if (el.sortableInstance) return
  
  const sortable = Sortable.create(el, {
    handle: '.drag-handler',
    animation: 150,
    // 先本地重排让表格立刻响应，再提交完整顺序。
    // 这里不做失败回滚：与后端的一致性由下次 fetchProblems 兜底，
    // 拖拽场景下回滚造成的视觉跳动比短暂的不一致更让人困惑。
    onEnd: async ({ newIndex, oldIndex }) => {
      if (newIndex === oldIndex) return

      const currRow = problemList.value.splice(oldIndex, 1)[0]
      problemList.value.splice(newIndex, 0, currRow)

      await saveCurrentOrder(false) // 拖拽是用户主动动作，需要确认"顺序已更新"
    }
  })

  // 实例挂在 DOM 节点上用于判重：fetchProblems 会反复调用 initSortable，
  // 不判重就会在同一 tbody 上叠加多个 Sortable，拖一次触发多次保存。
  el.sortableInstance = sortable
}

onMounted(() => {
  fetchProblems()
})
</script>

<style scoped>
.problems-container { padding: 10px; }
.manage-tools { background: var(--bg-surface-hover); padding: 20px; border-radius: 8px; border: 1px dashed var(--border-base); display: flex; align-items: center; gap: 15px; }
.tool-label { font-weight: 600; color: var(--text-regular); white-space: nowrap; }
.add-input { width: 350px; box-shadow: var(--shadow-sm); }
.add-btn { background-color: var(--accent-primary); color: var(--text-on-accent); border-top-left-radius: 0; border-bottom-left-radius: 0; }
.tool-tip { font-size: 12px; color: var(--text-secondary); margin-left: 10px; }
.id-tag { font-family: monospace; background: var(--bg-surface-hover); padding: 2px 6px; border-radius: 4px; color: var(--text-regular); }
.problem-title { font-weight: 500; color: var(--text-primary); }
.delete-btn:hover { background-color: var(--accent-danger-light); border-color: var(--el-color-danger-light-7); color: var(--accent-danger); }

/* 难度标签样式 */
.diff-tag { font-weight: bold; font-size: 13px; }

.drag-handler:hover { color: var(--accent-primary) !important; transform: scale(1.1); transition: all 0.2s; }
</style>
