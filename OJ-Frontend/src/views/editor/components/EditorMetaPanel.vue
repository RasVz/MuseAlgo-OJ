<template>
  <aside class="meta-panel">
    <header class="panel-header">
      <span>发布设置</span>
      <span class="panel-channel">{{ channelLabel }}</span>
    </header>

    <div class="field">
      <label>分类</label>
      <el-select
        :model-value="form.category"
        size="small"
        :disabled="channel === 'solution'"
        class="full"
        @update:model-value="(value) => emit('update-field', 'category', value)"
      >
        <el-option v-for="item in categories" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
    </div>

    <div class="field">
      <label>可见范围</label>
      <el-select
        :model-value="form.privacy"
        size="small"
        class="full"
        @update:model-value="(value) => emit('update-field', 'privacy', Number(value))"
      >
        <el-option label="公开：所有用户可见" :value="0" />
        <el-option label="仅自己可见" :value="1" />
      </el-select>
    </div>

    <div class="field">
      <label>标签<span class="hint">最多 8 个，用于检索</span></label>
      <div class="tag-trigger" @click="tagDialogVisible = true">
        <div v-if="form.tags.length" class="tag-list">
          <el-tag v-for="tag in form.tags" :key="tag" size="small" effect="plain" closable @close.stop="removeTag(tag)">{{ tag }}</el-tag>
        </div>
        <span v-else class="placeholder">选择知识点、来源或语言</span>
        <el-icon><ArrowDown /></el-icon>
      </div>
    </div>

    <div class="field">
      <label>摘要<span class="hint">选填，不填按正文前段提取</span></label>
      <el-input
        :model-value="form.summary"
        type="textarea"
        :rows="3"
        maxlength="256"
        show-word-limit
        placeholder="一句话说明这篇文章讲什么"
        @update:model-value="(value) => emit('update-field', 'summary', value)"
      />
    </div>

    <template v-if="channel === 'article'">
      <div class="field">
        <label>关联练习<span class="hint">最多 12 道</span></label>
        <div class="inline-input">
          <el-input
            v-model="relatedInput"
            size="small"
            placeholder="题目 ID"
            @keyup.enter="addRelatedProblem"
          />
          <el-button size="small" @click="addRelatedProblem">添加</el-button>
        </div>
        <div v-if="form.relatedProblemIds.length" class="tag-list related">
          <el-tag
            v-for="id in form.relatedProblemIds"
            :key="id"
            size="small"
            effect="plain"
            closable
            @close="removeRelatedProblem(id)"
          >题目 #{{ id }}</el-tag>
        </div>
      </div>
    </template>

    <div v-else-if="channel === 'solution'" class="field">
      <label>关联题目</label>
      <el-tag v-if="form.problemId" type="primary" effect="light">题目 #{{ form.problemId }}</el-tag>
      <span v-else class="warning">未关联题目，无法发布</span>
    </div>

    <p class="panel-footer">正文以 Markdown 保存，编辑器内不提供单独预览。</p>

    <PostTagSelectorDialog
      v-model="tagDialogVisible"
      :tag-groups="tagGroups"
      :selected-tags="form.tags"
      @confirm="(tags) => emit('update-field', 'tags', tags)"
    />
  </aside>
</template>

<script setup>
// 发布设置面板：只读父组件的表单，所有修改通过 update-field 回传。
// 直接改 props 会触发 vue/no-mutating-props，也让数据流向难以追踪。
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { channelByKey } from '../channels'
import PostTagSelectorDialog from '@/views/post/components/PostTagSelectorDialog.vue'

const props = defineProps({
  form: { type: Object, required: true },
  channel: { type: String, default: 'article' },
  tagGroups: { type: Array, default: () => [] }
})

const emit = defineEmits(['update-field'])

const channelLabel = computed(() => channelByKey(props.channel).label)
const categories = computed(() => channelByKey(props.channel).categories)
const tagDialogVisible = ref(false)
const relatedInput = ref('')

const removeTag = (tag) => {
  emit('update-field', 'tags', props.form.tags.filter((item) => item !== tag))
}

const addRelatedProblem = () => {
  const id = Number(relatedInput.value)
  if (!Number.isSafeInteger(id) || id <= 0) {
    ElMessage.warning('请输入有效的题目 ID')
    return
  }
  if (props.form.relatedProblemIds.includes(id)) {
    ElMessage.info('该题目已关联')
    return
  }
  if (props.form.relatedProblemIds.length >= 12) {
    ElMessage.warning('最多关联 12 道练习题')
    return
  }
  emit('update-field', 'relatedProblemIds', [...props.form.relatedProblemIds, id])
  relatedInput.value = ''
}

const removeRelatedProblem = (id) => {
  emit('update-field', 'relatedProblemIds', props.form.relatedProblemIds.filter((item) => item !== id))
}
</script>

<style scoped>
.meta-panel {
  position: sticky;
  top: 80px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  box-shadow: var(--shadow-sm);
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border-light);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.panel-channel { color: var(--text-secondary); font-size: 12px; font-weight: 400; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field > label {
  display: flex;
  align-items: baseline;
  gap: 6px;
  color: var(--text-secondary);
  font-size: 12px;
}
.hint { color: var(--text-placeholder); font-size: 11px; }
.full { width: 100%; }
.tag-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 32px;
  padding: 4px 8px;
  border: 1px solid var(--border-base);
  border-radius: 6px;
  cursor: pointer;
}
.tag-trigger:hover { border-color: var(--accent-primary); }
.tag-list { display: flex; flex-wrap: wrap; gap: 4px; }
.tag-list.related { margin-top: 6px; }
.placeholder { color: var(--text-placeholder); font-size: 12px; }
.inline-input { display: flex; gap: 6px; }
.warning { color: var(--accent-danger); font-size: 12px; }
.panel-footer { margin: 0; color: var(--text-placeholder); font-size: 11px; line-height: 1.6; }
</style>
