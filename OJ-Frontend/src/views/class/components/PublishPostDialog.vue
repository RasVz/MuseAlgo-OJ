<template>
  <el-dialog
    :model-value="visible"
    title="✨ 记录班级新鲜事"
    width="640px"
    :close-on-click-modal="false"
    destroy-on-close
    class="publish-dialog"
    @update:model-value="$emit('update:visible', $event)"
  >
    <div class="dialog-form">
      <el-input
        v-model="form.title"
        placeholder="给动态起个响亮的标题吧（选填）"
        class="mb-15 custom-input"
        size="large"
        maxlength="60"
        show-word-limit
      />
      <el-input
        v-model="form.content"
        type="textarea"
        :rows="6"
        placeholder="分享你的想法、问题或者有趣的事情..."
        class="mb-15 custom-textarea"
        resize="none"
        maxlength="1000"
        show-word-limit
      />

      <div class="upload-area">
        <div class="upload-title">配图（可选）</div>
        <el-upload
          class="post-uploader"
          action="/api/file/upload"
          list-type="picture-card"
          :headers="uploadHeaders"
          :limit="9"
          :on-exceed="handleExceed"
          :on-success="handleUploadSuccess"
          :on-remove="handleRemove"
          v-model:file-list="fileList"
          accept="image/*"
        >
          <el-icon><Plus /></el-icon>
          <template #tip>
            <div class="el-upload__tip">最多上传 9 张图片，单张不超过 5MB</div>
          </template>
        </el-upload>
      </div>
    </div>

    <template #footer>
      <span class="dialog-footer">
        <el-button @click="$emit('update:visible', false)" round>取 消</el-button>
        <el-button type="primary" :loading="publishing" @click="submit" round>
          发 布
        </el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup>
// 班级动态发布弹窗：文本 + 最多 9 张配图。
// el-upload 直传 /api/file/upload 而不走统一的 api 层，因为需要 Element 的
// 逐文件上传进度与列表状态，这些是组件内建的。
import { ref, watch } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { createPost } from '@/api/post'
import { useUserStore } from '@/stores/userStore'

const props = defineProps({
  visible: { type: Boolean, default: false },
  classId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:visible', 'published'])

const userStore = useUserStore()
const publishing = ref(false)
const form = ref({ title: '', content: '' })
const fileList = ref([])

// Token 必须手动塞进请求头：el-upload 走的是原生 XHR，绕过了 axios 拦截器。
const uploadHeaders = ref({
  Authorization: `Bearer ${userStore.getAccessToken()}`
})

// 关闭弹窗时清空表单：否则上次发到一半的内容会留在输入框里，
// 用户容易把两段内容一起发出去。
watch(
  () => props.visible,
  (val) => {
    if (!val) {
      form.value = { title: '', content: '' }
      fileList.value = []
    }
  }
)

const handleExceed = () => {
  ElMessage.warning('最多只能上传 9 张图片哦！')
}

const handleUploadSuccess = (response, uploadFile) => {
  if (response.code === 200) {
    uploadFile.url = response.data
    ElMessage.success('图片上传成功')
  } else {
    ElMessage.error(response.message || '图片上传失败')
  }
}

const handleRemove = () => {}

// 文本与图片至少要有一项：两者都空的动态对其他人没有信息量。
const submit = async () => {
  if (!form.value.content.trim() && fileList.value.length === 0) {
    ElMessage.warning('动态内容和图片不能同时为空')
    return
  }

  publishing.value = true
  try {
    // 图片地址取 response.data 还是 file.url 取决于上传是否已完成：
  // el-upload 在成功回调前就已把本地临时 URL 放进 file.url，
  // 两者都要取并过滤掉还没拿到真实地址的条目。
    const validImages = fileList.value
      .map(file => (file.response ? file.response.data : file.url))
      .filter(url => url)

    // classId 让这条动态只在本班可见：不传的话会变成全站公开的帖子。
    const postData = {
      classId: props.classId,
      title: form.value.title?.trim() || '',
      content: form.value.content?.trim() || '',
      images: validImages.join(',')
    }

    const res = await createPost(postData)

    if (res.code === 200) {
      ElMessage.success('发布成功！')
      emit('update:visible', false)
      form.value = { title: '', content: '' }
      fileList.value = []
      emit('published')
    } else {
      ElMessage.error(res.message || '发布失败')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('发布异常，请检查网络')
  } finally {
    publishing.value = false
  }
}
</script>

<style scoped>
.dialog-form {
  padding: 6px 0 2px;
}

.mb-15 {
  margin-bottom: 15px;
}

:deep(.custom-input .el-input__wrapper),
:deep(.custom-textarea .el-textarea__inner) {
  box-shadow: none;
  background-color: var(--bg-input);
  border-radius: 10px;
}

:deep(.custom-input .el-input__wrapper:hover),
:deep(.custom-textarea .el-textarea__inner:hover),
:deep(.custom-input .el-input__wrapper.is-focus),
:deep(.custom-textarea .el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
  background-color: var(--bg-surface);
}

.upload-area {
  margin-top: 6px;
  border: 1px dashed var(--border-light);
  border-radius: 12px;
  padding: 12px;
  background: var(--bg-surface-hover);
}

.upload-title {
  font-size: 13px;
  color: var(--text-regular);
  margin-bottom: 8px;
  font-weight: 600;
}
</style>
