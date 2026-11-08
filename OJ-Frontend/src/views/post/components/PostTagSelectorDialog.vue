<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="800px"
    class="post-tag-selector-dialog"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="tag-dialog-header">
      <el-tabs v-model="activeTagTab" class="tag-dialog-tabs">
        <el-tab-pane label="知识点" name="knowledge" />
        <el-tab-pane label="来源" name="source" />
        <el-tab-pane label="语言" name="language" />
      </el-tabs>
      <el-input v-model="tagSearch" placeholder="搜索当前标签" :prefix-icon="Search" clearable class="tag-search-input" />
    </div>

    <div v-if="temporaryTags.length" class="tag-selected-area">
      <span>已选 {{ temporaryTags.length }}<template v-if="hasSelectionLimit"> / {{ maxTags }}</template></span>
      <div class="tag-selected-list">
        <el-tag v-for="tag in temporaryTags" :key="tag" closable effect="dark" class="selected-tag" @close="toggleTag(tag)">{{ tag }}</el-tag>
      </div>
    </div>

    <el-divider />

    <div class="tag-options-scroll">
      <el-empty v-if="currentTagGroups.length === 0" :image-size="72" description="暂无匹配标签" />
      <section v-for="group in currentTagGroups" :key="group.label" class="tag-option-group">
        <h3>{{ group.label }}</h3>
        <div class="tag-option-list">
          <button
            v-for="tag in group.options"
            :key="tag"
            type="button"
            class="tag-option"
            :class="{ active: temporaryTags.includes(tag) }"
            @click="toggleTag(tag)"
          >{{ tag }}</button>
        </div>
      </section>
    </div>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="handleConfirm">确认选择</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'

/**
 * 帖子标签选择弹窗（从 PostEditorView 抽离）。
 * 打开时从 selectedTags 拷贝一份临时选择，确认时通过 confirm 事件一次性提交。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '选择标签' },
  /** 后端标签分组：[{ label, options: string[] }] */
  tagGroups: { type: Array, default: () => [] },
  /** 当前已选标签（对话框打开时作为临时选择的初始值） */
  selectedTags: { type: Array, default: () => [] },
  /** 省略时不限制数量；编辑文章时保留原有的 8 个上限。 */
  maxTags: { type: Number, default: 8 }
})

const emit = defineEmits(['update:modelValue', 'confirm'])

const activeTagTab = ref('knowledge')
const tagSearch = ref('')
const temporaryTags = ref([])
const hasSelectionLimit = computed(() => Number.isFinite(props.maxTags) && props.maxTags > 0)

const knowledgeGroupLabels = new Set(['算法基础', '搜索', '数据结构', '动态规划', '数学', '图论', '字符串'])

const currentTagGroups = computed(() => {
  const query = tagSearch.value.trim().toLowerCase()
  const groups = props.tagGroups.filter((group) => {
    if (activeTagTab.value === 'knowledge') return knowledgeGroupLabels.has(group.label)
    if (activeTagTab.value === 'source') return group.label === '题目平台' || group.label === '赛事体系'
    return group.label === '编程语言'
  })

  return groups
    .map((group) => ({
      ...group,
      // 将"搜索"并入算法知识域，用户仍能在六个知识域中定位标签。
      label: group.label === '搜索' ? '算法基础' : group.label,
      options: group.options.filter((tag) => !query || tag.toLowerCase().includes(query))
    }))
    .filter((group) => group.options.length > 0)
    .reduce((merged, group) => {
      const existing = merged.find((item) => item.label === group.label)
      if (existing) existing.options.push(...group.options)
      else merged.push({ ...group, options: [...group.options] })
      return merged
    }, [])
})

watch(() => props.modelValue, (visible) => {
  if (visible) {
    temporaryTags.value = [...props.selectedTags]
    tagSearch.value = ''
  }
})

const toggleTag = (tag) => {
  const index = temporaryTags.value.indexOf(tag)
  if (index >= 0) {
    temporaryTags.value.splice(index, 1)
  } else if (!hasSelectionLimit.value || temporaryTags.value.length < props.maxTags) {
    temporaryTags.value.push(tag)
  } else {
    ElMessage.warning(`最多选择 ${props.maxTags} 个标签`)
  }
}

const handleConfirm = () => {
  emit('confirm', [...temporaryTags.value])
  emit('update:modelValue', false)
}
</script>

<style scoped>
.tag-dialog-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.tag-dialog-tabs { flex: 1; }
.tag-dialog-tabs :deep(.el-tabs__header) { margin: 0; }
.tag-search-input { width: 210px; }
.tag-selected-area { display: flex; flex-direction: column; gap: 8px; margin: 16px 0 4px; padding: 12px; border-radius: 6px; background: var(--bg-surface-hover); color: var(--text-regular); font-size: 13px; }
.tag-selected-list { display: flex; flex-wrap: wrap; gap: 8px; }
.selected-tag { border: 0; background: var(--accent-primary-dark); }
.tag-options-scroll { min-height: 235px; max-height: 390px; overflow-y: auto; padding-right: 4px; }
.tag-option-group { margin-bottom: 22px; }
.tag-option-group h3 { margin: 0 0 10px; color: var(--text-primary); font-size: 14px; }
.tag-option-list { display: flex; flex-wrap: wrap; gap: 9px; }
.tag-option { padding: 6px 12px; border: 1px solid transparent; border-radius: 4px; background: var(--el-fill-color-light); color: var(--text-regular); cursor: pointer; font-size: 13px; transition: all .18s; }
.tag-option:hover { background: var(--bg-surface-hover); }
.tag-option.active { border-color: var(--accent-primary-dark); background: var(--accent-primary-dark); color: var(--text-on-accent); font-weight: 500; }
</style>
