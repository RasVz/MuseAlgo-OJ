<template>
  <el-form :model="form" label-width="80px" class="meta-form">
    <el-row :gutter="20">
      <el-col :span="8">
        <el-form-item v-if="!isSolution" label="分类">
          <el-select v-model="localForm.category" placeholder="选择分类" style="width:100%">
            <template v-if="isDiscussion">
              <el-option label="提问" value="question" />
              <el-option label="经验分享" value="experience" />
              <el-option label="比赛讨论" value="contest-discussion" />
            </template>
            <template v-else>
              <el-option label="算法·理论" value="algorithm" />
              <el-option label="生活·游记" value="life" />
              <el-option label="休闲·娱乐" value="leisure" />
              <el-option label="竞赛·复盘" value="contest" />
            </template>
          </el-select>
        </el-form-item>
        <el-form-item v-else label="内容类型">
          <el-tag type="primary" effect="light">题解 · 关联题目 #{{ localForm.problemId }}</el-tag>
        </el-form-item>
      </el-col>
      <el-col :span="8">
        <el-form-item label="可见范围">
          <el-select v-model="localForm.privacy" style="width:100%">
            <el-option label="公开：所有用户可见" :value="0" />
            <el-option label="仅自己可见" :value="1" />
          </el-select>
        </el-form-item>
      </el-col>
      <el-col :span="16">
        <el-form-item label="标签">
          <div class="tag-selector-trigger" @click="tagDialogVisible = true">
            <div v-if="localForm.tags.length" class="selected-tag-preview">
              <el-tag v-for="tag in localForm.tags" :key="tag" size="small" effect="plain" @close.stop="removeSelectedTag(tag)" closable>{{ tag }}</el-tag>
            </div>
            <span v-else class="tag-placeholder">选择知识点、来源或语言（最多 8 个）</span>
            <el-icon class="tag-arrow"><ArrowDown /></el-icon>
          </div>
          <div class="tag-help">题解可不选标签；标签仅用于补充检索，不会自动继承题目标签。</div>
        </el-form-item>
      </el-col>
    </el-row>
    <el-form-item v-if="isArticle" label="关联练习">
      <div class="related-problem-editor">
        <div class="related-problem-input-row">
          <el-input v-model="relatedProblemInput" placeholder="输入题目 ID，最多关联 12 道" @keyup.enter="addRelatedProblem" />
          <el-button @click="addRelatedProblem">添加题目</el-button>
        </div>
        <div v-if="localForm.relatedProblemIds.length" class="related-problem-chip-list">
          <el-tag v-for="id in localForm.relatedProblemIds" :key="id" closable effect="plain" @close="removeRelatedProblem(id)">题目 #{{ id }}</el-tag>
        </div>
        <div class="tag-help">读者可从文章直接跳转练习；题解固定归属当前题目，不在这里关联。</div>
      </div>
    </el-form-item>
    <el-form-item label="摘要">
      <el-input
        v-model="localForm.summary"
        type="textarea"
        :rows="2"
        placeholder="文章摘要（选填，不填会自动提取正文前段）"
      />
    </el-form-item>
  </el-form>

  <PostTagSelectorDialog
    v-model="tagDialogVisible"
    :tag-groups="tagGroups"
    :selected-tags="localForm.tags"
    @confirm="localForm.tags = $event"
  />
</template>

<script setup>
// 文章元信息表单：标题、分类、标签、简介、可见性与关联题目。
// 标签选项来自服务端，加载完成的时刻通过 tagsReady 暴露给父组件——
// 父组件需要等标签集就绪后才能过滤历史文章里的自由文本标签。
import { ref, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { getPostTagOptions } from '@/api/post'
import PostTagSelectorDialog from './PostTagSelectorDialog.vue'

const props = defineProps({
  form: { type: Object, required: true },
  isSolution: { type: Boolean, default: false },
  isArticle: { type: Boolean, default: false },
  isDiscussion: { type: Boolean, default: false }
})

const emit = defineEmits(['update:form'])

// 本地副本 + 双向 watch：与 ProblemBasicForm 同一种模式。
// 直接改 props.form 会让父组件无法控制赋值时机；不加深比较则父子互相赋值会成环。
const localForm = ref(structuredClone(props.form))
watch(() => props.form, (v) => {
  localForm.value = structuredClone(v)
}, { deep: true })
watch(localForm, () => {
  const next = structuredClone(localForm.value)
  if (JSON.stringify(next) === JSON.stringify(props.form)) return // 父组件同步来的，不回传
  emit('update:form', next)
}, { deep: true })

const tagGroups = ref([])
const tagDialogVisible = ref(false)
const relatedProblemInput = ref('')

const allowedTags = ref(new Set())

// 标签选项就绪信号：父组件加载详情（需过滤历史自由文本标签）前 await 本 Promise，
// 避免竞态导致合法标签被误清空（与拆分前"先 fetchTagOptions 再 loadPostDetail"语义一致）。
let resolveTagsReady
const tagsReady = new Promise((resolve) => { resolveTagsReady = resolve })

onMounted(async () => {
  const res = await getPostTagOptions()
  if (res.code === 200) {
    tagGroups.value = Object.entries(res.data || {}).map(([label, options]) => ({ label, options }))
    allowedTags.value = new Set(tagGroups.value.flatMap(group => group.options))
  }
  resolveTagsReady()
})

const removeSelectedTag = (tag) => {
  localForm.value.tags = localForm.value.tags.filter((item) => item !== tag)
}

const addRelatedProblem = () => {
  const id = Number(relatedProblemInput.value)
  if (!Number.isSafeInteger(id) || id <= 0) return ElMessage.warning('请输入有效的题目 ID')
  if (localForm.value.relatedProblemIds.includes(id)) return ElMessage.info('该题目已关联')
  if (localForm.value.relatedProblemIds.length >= 12) return ElMessage.warning('最多关联 12 道练习题')
  localForm.value.relatedProblemIds.push(id)
  relatedProblemInput.value = ''
}

const removeRelatedProblem = (id) => {
  localForm.value.relatedProblemIds = localForm.value.relatedProblemIds.filter((item) => item !== id)
}

defineExpose({ allowedTags, tagsReady })
</script>

<style scoped>
.tag-selector-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--border-base);
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 0.2s;
  min-height: 38px;
  flex: 1;
}
.tag-selector-trigger:hover { border-color: var(--accent-primary); }
.selected-tag-preview { display: flex; flex-wrap: wrap; gap: 6px; }
.tag-placeholder { color: var(--text-placeholder); font-size: 13px; }
.tag-arrow { color: var(--text-secondary); }
.tag-help { color: var(--text-secondary); font-size: 12px; line-height: 1.6; margin-top: 4px; }
.related-problem-input-row { display: flex; gap: 8px; }
.related-problem-chip-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
</style>
