<template>
  <div class="editor-container">
    <div class="editor-header">
      <el-input
        v-model="postForm.title"
        placeholder="输入文章标题..."
        class="title-input"
      />
      <div class="header-actions">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">
          {{ isEdit ? '更新文章' : '发布文章' }}
        </el-button>
      </div>
    </div>

    <div class="editor-main">
      <PostMetaForm
        ref="metaFormRef"
        :form="postForm"
        :is-solution="isSolution"
        :is-article="isArticle"
        :is-discussion="isDiscussion"
        @update:form="Object.assign(postForm, $event)"
      />

      <div class="content-editor">
        <div class="editor-toolbar">
          <div class="editor-mode-switch">
            <button type="button" :class="{ active: editorMode === 'write' }" @click="editorMode = 'write'">编辑</button>
            <button type="button" :class="{ active: editorMode === 'preview' }" @click="editorMode = 'preview'">预览</button>
          </div>
          <div class="editor-tools">
            <span>{{ draftStatus }}</span>
            <el-upload :show-file-list="false" :http-request="uploadAndInsertImage" accept="image/*">
              <el-button size="small" :loading="uploading"><el-icon><Picture /></el-icon>插入图片</el-button>
            </el-upload>
          </div>
        </div>
        <el-input
          v-show="editorMode === 'write'"
          v-model="postForm.content"
          type="textarea"
          :rows="24"
          placeholder="在这里开始撰写正文（支持 Markdown、代码块与 LaTex）..."
          class="main-textarea"
        />
        <article v-show="editorMode === 'preview'" class="markdown-preview markdown-body" v-html="previewHtml" />
      </div>
    </div>
  </div>
</template>

<script setup>
// 文章编辑器（弹窗内嵌页，通常以新标签页打开）：写讨论贴 / 题解 / 博客文章。
//
// 核心机制是"本地草稿"：编辑内容防抖存 localStorage，关闭页面前自动存一次，
// 下次进入同一篇（同一 draftKey）时询问是否恢复。草稿键里带 problemId，
// 因为"为题目 A 写的题解"和"为题目 B 写的题解"是两篇不同的文章，不能共用草稿。
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import { getPostDetail, createPost, updatePost } from '@/api/post'
import { uploadImage } from '@/api/user'
import { createMarkdown } from '@/utils/markdown'
import PostMetaForm from './components/PostMetaForm.vue'

const route = useRoute()

const isEdit = ref(false)
const saving = ref(false)
const uploading = ref(false)
const editorMode = ref('write')
const draftStatus = ref('草稿会自动保存到本机')
const initialSnapshot = ref('')
const serverUpdatedAt = ref(0)
const metaFormRef = ref(null)
const draftKey = computed(() => `musealgo:post-draft:${route.params.id || `new:${route.query.problemId || 'article'}`}`)

const postForm = ref({
  id: null,
  title: '',
  type: 2,
  category: 'algorithm',
  tags: [],
  summary: '',
  content: '',
  problemId: null,
  privacy: 0,
  relatedProblemIds: []
})

const isSolution = computed(() =>
  Number(postForm.value.type) === 1 || postForm.value.category === 'solution' || Boolean(postForm.value.problemId)
)
const isArticle = computed(() => Number(postForm.value.type) === 2)
const isDiscussion = computed(() => route.name === 'DiscussionCreate')

const toTagArray = (value) => {
  if (Array.isArray(value)) return value.filter(Boolean)
  return String(value || '').replace(/\[|\]|"/g, '').split(',').map(tag => tag.trim()).filter(Boolean)
}

// 编辑器本地预览：html=false 防 XSS、不启用 LaTeX（正文渲染由 PostContent 负责完整渲染）
const md = createMarkdown({ html: false, math: false })
const previewHtml = computed(() => md.render(postForm.value.content || '*开始撰写后，这里会显示 Markdown 预览。*'))
// 快照只取"内容性字段"：saving / uploading 这类状态变了不代表内容变了，
// 把它们算进来会让用户什么都没改也被判定为已修改。
const currentSnapshot = () => JSON.stringify({
  title: postForm.value.title, category: postForm.value.category, tags: postForm.value.tags,
  summary: postForm.value.summary, content: postForm.value.content, problemId: postForm.value.problemId,
  relatedProblemIds: postForm.value.relatedProblemIds,
  privacy: postForm.value.privacy
})
const isDirty = computed(() => currentSnapshot() !== initialSnapshot.value)

// 只在有改动时存草稿：每次进入都要覆盖写一遍同样的内容没有意义，
// 还会白白消耗 localStorage 配额。
const saveLocalDraft = () => {
  if (!isDirty.value) return
  localStorage.setItem(draftKey.value, JSON.stringify({ form: postForm.value, savedAt: Date.now() }))
  draftStatus.value = '已自动保存到本机'
}

// 恢复草稿前必须比较时间戳：编辑模式下若服务端版本比本地草稿还新，
// 直接恢复会用旧内容覆盖别人（或自己在别处）的修改。
const restoreLocalDraft = async () => {
  const raw = localStorage.getItem(draftKey.value)
  if (!raw) return
  try {
    const draft = JSON.parse(raw)
    if (draft?.form?.content || draft?.form?.title) {
      const savedAt = Number(draft.savedAt) || 0
      const serverChangedAfterDraft = isEdit.value && serverUpdatedAt.value > savedAt
      await ElMessageBox.confirm(
        serverChangedAfterDraft
          ? '检测到较早的本地草稿，服务器上的文章可能已有更新。仍要恢复本地草稿吗？'
          : '检测到未发布的本地草稿，是否恢复？',
        '恢复草稿',
        { confirmButtonText: '恢复', cancelButtonText: '暂不恢复', type: 'info' }
      )
      postForm.value = { ...postForm.value, ...draft.form, tags: toTagArray(draft.form.tags) }
      ElMessage.success('已恢复本地草稿')
    }
  } catch (error) {
    // Element Plus 的取消操作会以 rejected promise 返回，草稿保留供下次恢复。
    if (!(error instanceof SyntaxError)) return
    localStorage.removeItem(draftKey.value)
  }
}

const handleBeforeUnload = (event) => {
  if (!isDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

const uploadAndInsertImage = async ({ file }) => {
  uploading.value = true
  try {
    const res = await uploadImage(file)
    if (res.code !== 200 || !res.data) throw new Error(res.message || '上传失败')
    postForm.value.content += `${postForm.value.content ? '\n\n' : ''}![${file.name}](${res.data})\n`
    ElMessage.success('图片已插入正文')
  } catch (error) {
    ElMessage.error(error.message || '图片上传失败')
  } finally {
    uploading.value = false
  }
}

onMounted(async () => {
  const id = route.params.id
  if (id) {
    isEdit.value = true
    postForm.value.id = id
    // 等待标签选项就绪后再加载详情（过滤历史自由文本标签需要合法标签集）
    await metaFormRef.value?.tagsReady
    await loadPostDetail(id)
  }
  // 来自「写题解」入口：固定为题解类型并关联题目。
  // post.type 与数据库约定一致：0-讨论贴、1-题解、2-博客文章。
  if (route.query.type === '2') {
    postForm.value.type = 1
    postForm.value.category = 'solution'
    if (route.query.problemId) {
      postForm.value.problemId = route.query.problemId
    }
  }
  if (!id && isDiscussion.value) {
    postForm.value.type = 0
    postForm.value.category = 'question'
  }
  initialSnapshot.value = currentSnapshot()
  await restoreLocalDraft()
  window.addEventListener('beforeunload', handleBeforeUnload)
})

// 标签回填要过白名单：早期允许自由输入标签，那些值已不在当前标签集里，
  // 直接放进受控选择器会渲染不出来，重新保存时也会被清掉。
const loadPostDetail = async (id) => {
  try {
    const res = await getPostDetail(id)
    if (res.code === 200 && res.data) {
      // 历史自由文本标签不再放回受控选择器（仅保留合法标签）；重新保存时会被清理。
      const allowedTags = metaFormRef.value?.allowedTags?.value ?? new Set()
      postForm.value = {
        id: res.data.id,
        title: res.data.title || '',
        type: res.data.type ?? 0,
        category: res.data.category || '',
        tags: toTagArray(res.data.tags).filter(tag => allowedTags.has(tag)),
        summary: res.data.summary || '',
        content: res.data.content || '',
        problemId: res.data.problemId || null,
        privacy: Number(res.data.privacy ?? 0),
        relatedProblemIds: (res.data.relatedProblems || []).map(problem => Number(problem.id)).filter(Number.isSafeInteger)
      }
      serverUpdatedAt.value = Date.parse(res.data.updateTime || '') || 0
    } else {
      ElMessage.error(res.message || '获取文章详情失败')
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('加载异常')
  }
}

// 取消不等于丢弃：先落盘草稿再关标签页，用户下次回来还能接着写。
const handleCancel = () => {
  saveLocalDraft()
  window.close() // 新标签页直接关闭
}

const handleSave = async () => {
  if (!postForm.value.title.trim()) {
    ElMessage.warning('文章标题不能为空')
    return
  }
  if (!postForm.value.content.trim()) {
    ElMessage.warning('文章正文不能为空')
    return
  }

  saving.value = true
  try {
    const reqData = { ...postForm.value }
    // type 与 category 必须成对设置：讨论贴与博客文章都不该带 problemId，
  // 否则题解列表里会混进普通文章。
    reqData.tags = postForm.value.tags.join(',')
    if (isSolution.value) {
      reqData.type = 1
      reqData.category = 'solution'
    } else if (isDiscussion.value) {
      reqData.type = 0
      reqData.problemId = null
    } else {
      reqData.type = 2
      reqData.problemId = null
    }

    let res;
    if (isEdit.value) {
      res = await updatePost(reqData)
    } else {
      res = await createPost(reqData)
    }

    if (res.code === 200) {
      // 草稿已完成使命：删掉，否则下次进来还会问用户"是否恢复这份草稿"
      localStorage.removeItem(draftKey.value)
      // 重置基线：否则保存成功后 isDirty 仍为 true，关页面时还会弹"确认离开"
      initialSnapshot.value = currentSnapshot()
      ElMessage.success(isEdit.value ? '文章更新成功！' : '文章发布成功！')
      setTimeout(() => {
        window.close() // 延迟关闭页面，回到调用页
      }, 1000)
    } else {
      ElMessage.error(res.message || '保存失败')
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('网络请求异常，请稍后重试')
  } finally {
    saving.value = false
  }
}

// 草稿防抖 700ms：内容改动频繁，逐次写 localStorage 是同步阻塞操作，
// 会拖慢打字。700ms 足以覆盖一次连续输入的间隔。
let draftTimer
watch(() => currentSnapshot(), () => {
  draftStatus.value = '正在保存草稿…'
  clearTimeout(draftTimer)
  draftTimer = setTimeout(saveLocalDraft, 700)
})

onBeforeUnmount(() => {
  clearTimeout(draftTimer)
  saveLocalDraft()
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<style scoped>
.editor-container {
  min-height: 100vh;
  background: var(--bg-app);
  display: flex;
  flex-direction: column;
}
.editor-header {
  height: 60px;
  background: var(--bg-header);
  display: flex;
  align-items: center;
  padding: 0 20px;
  box-shadow: var(--shadow-sm);
  position: sticky;
  top: 0;
  z-index: 100;
}
.title-input {
  flex: 1;
  margin-right: 20px;
}
.title-input :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
  padding: 0;
}
.title-input :deep(input) {
  font-size: 24px;
  font-weight: bold;
  color: var(--text-primary);
}
.header-actions {
  display: flex;
  gap: 10px;
}
.editor-main {
  flex: 1;
  max-width: 1000px;
  margin: 20px auto;
  width: 100%;
  background: var(--bg-surface);
  padding: 30px;
  border-radius: 4px;
  box-shadow: var(--shadow-sm);
}
.editor-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.editor-mode-switch {
  display: flex;
  gap: 4px;
  padding: 3px;
  background: var(--border-light);
  border-radius: 6px;
}
.editor-mode-switch button {
  padding: 5px 16px;
  border: none;
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
  color: var(--text-secondary);
  font-size: 13px;
  transition: all 0.2s;
}
.editor-mode-switch button.active {
  background: var(--bg-surface);
  color: var(--accent-primary);
  box-shadow: var(--shadow-sm);
  font-weight: 600;
}
.editor-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-secondary);
  font-size: 12px;
}
.main-textarea :deep(.el-textarea__inner) {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 14px;
  line-height: 1.7;
  padding: 16px;
  border-radius: 6px;
}
.markdown-preview {
  min-height: 500px;
  padding: 20px;
  border: 1px solid var(--border-light);
  border-radius: 6px;
  background: var(--bg-surface);
}
</style>
