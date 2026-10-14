<template>
  <NavbarContext>
    <div class="navbar-filter-container">
      
      <div class="left-filters">
        <span class="filter-label">筛选条件:</span>
        
        <el-select 
          v-model="localDifficulty" 
          placeholder="题目难度" 
          clearable 
          class="nav-select difficulty-select"
          popper-class="difficulty-dropdown"
          @change="updateFilters"
        >
          <template #prefix>
            <span
              v-if="selectedDifficulty"
              class="selected-difficulty-swatch"
              :style="{ backgroundColor: selectedDifficulty.color }"
            />
          </template>
          <el-option label="所有" value="" />
          <el-option
            v-for="range in DIFFICULTY_RANGES"
            :key="range.key"
            :label="range.label"
            :value="range.key"
          >
            <span class="difficulty-option">
              <span class="difficulty-swatch" :style="{ backgroundColor: range.color }" />
              <span class="difficulty-label">{{ range.label }}</span>
              <span class="difficulty-score">{{ range.min }} - {{ range.max }}</span>
            </span>
          </el-option>
        </el-select>

        <div class="tag-trigger" @click="dialogVisible = true">
          <span v-if="selectedFilterCount === 0" class="placeholder">算法 / 来源 / 状态</span>
          <span v-else class="selected-count">已选 {{ selectedFilterCount }} 项</span>
          <el-icon class="arrow-icon"><ArrowDown /></el-icon>
        </div>
      </div>

      <div class="spacer"></div>

      <div class="right-actions">
        <el-input 
          v-model="keyword" 
          placeholder="算法、标题或题目编号" 
          prefix-icon="Search"
          class="nav-input"
          @keyup.enter="updateFilters"
        />
        <el-button type="primary" @click="updateFilters">搜索</el-button>
      </div>

    </div>
  </NavbarContext>

  <el-dialog
    v-model="dialogVisible"
    title="选择标签"
    width="800px"
    class="tag-selector-dialog"
    destroy-on-close
  >
    <div class="dialog-header-row">
      <el-tabs v-model="activeTab" class="tag-tabs">
        <el-tab-pane label="算法" name="algorithm" />
        <el-tab-pane label="来源" name="source" />
        <el-tab-pane label="状态" name="status" />
      </el-tabs>
      <el-input 
        v-model="tagSearch" 
        placeholder="搜索全部标签" 
        prefix-icon="Search" 
        class="tag-search-input"
        clearable
      />
    </div>

    <div class="selected-area" v-if="temporaryFilterCount > 0">
      <span class="label">已选条件 ({{ temporaryFilterCount }})</span>
      <div class="tags-wrapper">
        <el-tag 
          v-for="tag in tempTags" 
          :key="tag" 
          closable 
          effect="dark"
          color="var(--el-color-primary)"
          class="selected-tag-item"
          @close="toggleTag(tag)"
        >
          {{ tag }}
        </el-tag>
        <el-tag
          v-if="tempSource"
          closable
          effect="dark"
          color="var(--el-color-primary)"
          class="selected-tag-item"
          @close="tempSource = ''"
        >
          来源: {{ tempSource }}
        </el-tag>
        <el-tag
          v-if="tempStatus !== ''"
          closable
          effect="dark"
          color="var(--el-color-primary)"
          class="selected-tag-item"
          @close="tempStatus = ''"
        >
          状态: {{ statusLabel(tempStatus) }}
        </el-tag>
      </div>
    </div>

    <el-divider class="dialog-divider" />

    <div class="tags-container scrollbar-custom">
      <el-empty v-if="currentTagGroups.length === 0" :image-size="72" description="暂无可用筛选项" />
      <div v-for="group in currentTagGroups" :key="group.name" class="tag-group">
        <div class="group-title">{{ group.name }}</div>
        <div class="group-items">
          <div
            v-for="item in group.items"
            :key="item.value"
            class="tag-choice"
            :class="{ active: isTemporaryOptionSelected(item) }"
            @click="toggleOption(item)"
          >
            {{ item.label }}
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmTags">确认</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup>
// 题库筛选栏：难度 / 关键词 / 标签 / 来源 / 个人解题状态的组合筛选。
// 筛选值先落在本地 ref，确认「搜索」后才 emit 给父组件发起请求——
// 边输入边搜会让每敲一个字都打一次接口。
import { ref, watch, computed, onMounted } from 'vue'
import NavbarContext from '@/components/NavbarContext.vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { DIFFICULTY_RANGES, getDifficultyRangeByKey } from '@/utils/difficulty'
import { getProblemFilterOptions } from '@/api/problem'

const props = defineProps(['filters'])
const emit = defineEmits(['update:filters', 'search'])

// --- 状态管理 ---
const localDifficulty = ref(props.filters.difficulty || '')
const keyword = ref(props.filters.keyword || '')
const localTags = ref(props.filters.tags || []) 
const localSource = ref(props.filters.source || '')
const localStatus = ref(props.filters.userStatus ?? '')

// 弹窗相关
const dialogVisible = ref(false)
const activeTab = ref('algorithm')
const tagSearch = ref('')
const tempTags = ref([]) 
const tempSource = ref('')
const tempStatus = ref('')
const selectedDifficulty = computed(() => getDifficultyRangeByKey(localDifficulty.value))

const DIMENSION_LABELS = {
  ALGORITHM: '算法设计',
  DATA_STRUCTURE: '数据结构',
  GRAPH: '图论',
  MATH: '数学',
  STRING: '字符串',
  OTHER: '其他标签'
}

// 后端返回的标签维度不全时用内置兜底表补齐：
// 没有它们，筛选弹窗里的「数据结构」「图论」等页签会整个空掉，用户以为功能坏了。
const fallbackTagsByDimension = {
  ALGORITHM: ['动态规划', '贪心', '二分查找', '搜索', '分治', '递归', '枚举', '模拟', '排序', '双指针', '前缀和', '差分'],
  DATA_STRUCTURE: ['数组', '链表', '栈与队列', '堆', '树', '并查集', '哈希表', '线段树', '树状数组', '单调栈', '单调队列'],
  GRAPH: ['最短路', '最小生成树', '拓扑排序', '连通性', '网络流', '二分图', 'LCA'],
  MATH: ['数论', '组合数学', '快速幂', '概率', '博弈论', '矩阵'],
  STRING: ['字符串匹配', '字典树', '哈希', '后缀数组', '回文自动机']
}

const filterOptions = ref({ tagsByDimension: fallbackTagsByDimension, sources: [] })
const statusOptions = [
  { value: 0, label: '未开始' },
  { value: 2, label: '尝试过' },
  { value: 1, label: '已通过' }
]

// 监听父组件 filters 变化并回填本地：用户在父页面点标签上的「x」时，
// 这里的勾选状态必须跟着清掉，否则下次打开弹窗会看到已经移除的标签仍被选中。
watch(() => props.filters, (newVal) => {
  if (newVal) {
    localDifficulty.value = newVal.difficulty || ''
    keyword.value = newVal.keyword || ''
    localTags.value = newVal.tags || []
    localSource.value = newVal.source || ''
    localStatus.value = newVal.userStatus ?? ''
  }
}, { deep: true })

const normalizeOptions = (groups) => Object.entries(groups || {}).map(([dimension, values]) => ({
  name: DIMENSION_LABELS[dimension] || dimension,
  items: [...new Set(values || [])].map((value) => ({ type: 'tag', value, label: value }))
}))

// 服务端标签与内置兜底表合并去重：兜底表里的标签服务端可能还没有，
// 但用户按习惯一定会去找「动态规划」「LCA」这类词。
const mergeTagGroups = (remoteGroups) => {
  const allDimensions = new Set([
    ...Object.keys(fallbackTagsByDimension),
    ...Object.keys(remoteGroups || {})
  ])
  return Object.fromEntries([...allDimensions].map((dimension) => [
    dimension,
    [...new Set([...(remoteGroups?.[dimension] || []), ...(fallbackTagsByDimension[dimension] || [])])]
  ]))
}

const currentTagGroups = computed(() => {
  const query = tagSearch.value.trim().toLowerCase()
  let groups = []
  if (activeTab.value === 'algorithm') {
    groups = normalizeOptions(filterOptions.value.tagsByDimension)
  } else if (activeTab.value === 'source') {
    groups = [{ name: '题目来源', items: (filterOptions.value.sources || []).map((value) => ({ type: 'source', value, label: value })) }]
  } else {
    groups = [{ name: '做题状态', items: statusOptions.map((item) => ({ ...item, type: 'status' })) }]
  }

  return groups
    .map((group) => ({ ...group, items: group.items.filter((item) => !query || item.label.toLowerCase().includes(query)) }))
    .filter((group) => group.items.length > 0)
})

const selectedFilterCount = computed(() => localTags.value.length + Number(Boolean(localSource.value)) + Number(localStatus.value !== ''))
const temporaryFilterCount = computed(() => tempTags.value.length + Number(Boolean(tempSource.value)) + Number(tempStatus.value !== ''))

// --- 方法 ---

// 打开弹窗时把当前选择拷进 temp* 变量：让用户在弹窗里可以反复试选、
// 点「取消」时不影响已生效的筛选条件。
watch(dialogVisible, (val) => {
  if (val) {
    tempTags.value = [...localTags.value]
    tempSource.value = localSource.value
    tempStatus.value = localStatus.value
    tagSearch.value = ''
  }
})

const toggleTag = (tag) => {
  const index = tempTags.value.indexOf(tag)
  if (index > -1) {
    tempTags.value.splice(index, 1)
  } else {
    tempTags.value.push(tag)
  }
}

const isTemporaryOptionSelected = (item) => {
  if (item.type === 'tag') return tempTags.value.includes(item.value)
  if (item.type === 'source') return tempSource.value === item.value
  return tempStatus.value === item.value
}

const toggleOption = (item) => {
  if (item.type === 'tag') {
    toggleTag(item.value)
  } else if (item.type === 'source') {
    tempSource.value = tempSource.value === item.value ? '' : item.value
  } else {
    tempStatus.value = tempStatus.value === item.value ? '' : item.value
  }
}

const statusLabel = (status) => statusOptions.find((item) => item.value === status)?.label || ''

// 确认后才落到本地并触发一次搜索：弹窗里选了什么用户看不见结果，
// 只有确定下来才值得发请求。update:filters 与 search 分开发，
// 前者让父组件更新筛选对象，后者让父组件重新拉数据。
const confirmTags = () => {
  localTags.value = [...tempTags.value]
  localSource.value = tempSource.value
  localStatus.value = tempStatus.value
  dialogVisible.value = false
  updateFilters()
}

const updateFilters = () => {
  emit('update:filters', {
    difficulty: localDifficulty.value,
    keyword: keyword.value,
    tags: localTags.value,
    source: localSource.value,
    userStatus: localStatus.value
  })
  emit('search')
}

// 选项加载失败时静默回退到内置标签表：筛选栏是题库页的主要入口，
// 因接口失败整块报错会让用户以为题库挂了。
const loadFilterOptions = async () => {
  try {
    const res = await getProblemFilterOptions()
    if (res.code !== 200 || !res.data) return
    filterOptions.value = {
      tagsByDimension: mergeTagGroups(res.data.tagsByDimension),
      sources: Array.isArray(res.data.sources) ? res.data.sources : []
    }
  } catch (error) {
    console.warn('加载题库筛选选项失败，已使用内置算法标签', error)
  }
}

onMounted(loadFilterOptions)
</script>

<style scoped>
.navbar-filter-container { display: flex; align-items: center; width: 100%; height: 100%; gap: 15px; }
.left-filters { display: flex; align-items: center; gap: 12px; }
.filter-label { font-weight: bold; color: var(--text-primary); white-space: nowrap; font-size: 15px; }
.nav-select { width: 180px; }
.spacer { flex: 1; }
.right-actions { display: flex; align-items: center; gap: 10px; }
.nav-input { width: 240px; }

.tag-trigger {
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  padding: 0 12px;
  height: 32px; 
  line-height: 32px;
  min-width: 180px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--bg-surface);
  color: var(--text-regular);
  transition: border-color 0.2s;
}
.tag-trigger:hover { border-color: var(--el-border-color-dark); }
.placeholder { color: var(--text-placeholder); font-size: 14px; }
.selected-count { color: var(--text-primary); font-weight: 500; font-size: 14px; }
.arrow-icon { margin-left: 8px; font-size: 12px; }

:global(.tag-selector-dialog .el-dialog__body) { padding-top: 10px; }
.dialog-header-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.tag-search-input { width: 200px; }

.selected-area { margin: 15px 0; background: var(--bg-app); padding: 10px; border-radius: 4px; display: flex; flex-direction: column; gap: 8px; }
.selected-area .label { font-size: 13px; color: var(--text-secondary); }
.tags-wrapper { display: flex; flex-wrap: wrap; gap: 8px; }
.selected-tag-item { border: none; font-size: 13px; }

.dialog-divider { margin: 10px 0 20px 0; }

.tag-group { margin-bottom: 20px; }
.group-title { font-weight: bold; color: var(--text-primary); margin-bottom: 10px; font-size: 14px; }
.group-items { display: flex; flex-wrap: wrap; gap: 10px; }

.tag-choice {
  padding: 6px 12px;
  background: var(--bg-input);
  color: var(--text-regular);
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
  border: 1px solid transparent;
}
.tag-choice:hover { background: var(--bg-surface-hover); }
.tag-choice.active {
  background: var(--el-color-primary); 
  color: var(--text-on-accent);
  font-weight: 500;
}

:global(.difficulty-dropdown) { min-width: 236px !important; }
:global(.difficulty-dropdown .el-select-dropdown__item) { height: 34px; }
.difficulty-option {
  display: grid;
  grid-template-columns: 10px 90px max-content;
  align-items: center;
  column-gap: 8px;
  width: 100%;
}
.difficulty-swatch { width: 10px; height: 10px; border-radius: 50%; }
.selected-difficulty-swatch { width: 10px; height: 10px; border-radius: 50%; display: block; }
.difficulty-label { color: var(--text-regular); font-weight: 500; white-space: nowrap; }
.difficulty-score {
  color: var(--text-regular);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
</style>
