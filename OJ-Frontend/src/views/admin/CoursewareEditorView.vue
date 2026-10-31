<template>
  <div class="courseware-create-container">
    <div class="page-header">
      <div class="title">录入新课件</div>
      <div class="actions">
        <el-button @click="handleClose">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存并发布</el-button>
      </div>
    </div>

    <div class="form-content">
      <el-tabs v-model="activeTab">
        <!-- Tab 1：Slidev Markdown 编辑（原有方式） -->
        <el-tab-pane label="Markdown 编辑（Slidev）" name="md">
          <el-form :model="form" ref="formRef" :rules="rules" label-position="top">
            <el-row :gutter="20">
              <el-col :span="8">
                <el-form-item label="课件标题" prop="title">
                  <el-input v-model="form.title" placeholder="请输入课件标题" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="可见性">
                  <el-radio-group v-model="form.privacy">
                    <el-radio :label="1">公开共享</el-radio>
                    <el-radio :label="0">私有 (仅自己可见)</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="关联题单ID">
                  <el-input v-model="form.problemListId" placeholder="选填，关联后学习页侧边栏将显示题目" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row>
              <el-col :span="24">
                <el-form-item label="简介备注">
                  <el-input v-model="form.description" placeholder="请输入简介备注" />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>

          <div class="editor-section">
            <div class="editor-title">Markdown (PPT内容)</div>
            <div class="editor-wrapper">
              <el-input
                v-model="form.markdown"
                type="textarea"
                placeholder="在此输入 Slidev Markdown 源码内容..."
              />
            </div>
          </div>
        </el-tab-pane>

        <!-- Tab 2：腾讯文档课件（上传本地 PPT 的轻便方式） -->
        <el-tab-pane label="腾讯文档课件（上传 PPT）" name="tencent">
          <el-alert type="info" :closable="false" class="tencent-guide">
            <template #title>
              <b>三步接入（动画可在网页端直接播放）：</b>
            </template>
            ① 打开腾讯文档（docs.qq.com），把本地 PPT 上传/导入为在线幻灯片。<br />
            ② 点右上角「分享」→ 链接分享 → 权限设为「获得链接的人可查看」→ 勾选「允许嵌入到其他网站」，填入本平台域名（如 localhost:5173 或部署域名）。<br />
            ③ 复制浏览器地址栏链接（形如 https://docs.qq.com/slide/xxx），粘贴到下方「嵌入链接」。
          </el-alert>

          <el-form :model="form" ref="formRef" :rules="rules" label-position="top" class="tencent-form">
            <el-row :gutter="20">
              <el-col :span="8">
                <el-form-item label="课件标题" prop="title">
                  <el-input v-model="form.title" placeholder="请输入课件标题" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="可见性">
                  <el-radio-group v-model="form.privacy">
                    <el-radio :label="1">公开共享</el-radio>
                    <el-radio :label="0">私有 (仅自己可见)</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="关联题单ID">
                  <el-input v-model="form.problemListId" placeholder="选填，关联后学习页侧边栏将显示题目" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="腾讯文档嵌入链接" prop="url">
              <el-input v-model="form.url" placeholder="https://docs.qq.com/slide/xxx" />
            </el-form-item>
            <el-form-item label="简介备注">
              <el-input v-model="form.description" type="textarea" :rows="2" placeholder="请输入简介备注" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
// 课件编辑器（弹窗内嵌页，通常由 window.open 打开）：两种建课件的方式。
// Tab1：上传 Markdown，由后端编译成 Slidev 静态课件（耗时 10~30 秒）。
// Tab2：填腾讯文档嵌入链接，直接外链挂载。
// 校验规则随 activeTab 变化，因此用自定义 validator 而不是写死一条 url 必填规则。
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { createCourseware, uploadMdCourseware } from '@/api/courseware'

const formRef = ref(null)
const submitting = ref(false)
const activeTab = ref('md')

const form = reactive({
  title: '',
  privacy: 1,
  problemListId: '',
  description: '',
  markdown: '',
  url: '',
  type: 0
})

const rules = {
  title: [{ required: true, message: '请输入课件标题', trigger: 'blur' }],
  url: [{
    validator: (rule, value, callback) => {
      if (activeTab.value === 'tencent' && !value.trim()) {
        callback(new Error('请输入腾讯文档嵌入链接'))
      } else if (activeTab.value === 'tencent' && !/^https?:\/\//.test(value.trim())) {
        callback(new Error('链接需以 http:// 或 https:// 开头'))
      } else {
        callback()
      }
    },
    trigger: 'blur'
  }]
}

const handleClose = () => {
  window.close()
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return

    submitting.value = true
    try {
      if (activeTab.value === 'md') {
        await handleSubmitMarkdown()
      } else {
        await handleSubmitTencent()
      }
    } finally {
      submitting.value = false
    }
  })
}

// === Tab 1：Markdown 编译为 Slidev 网页 ===
const handleSubmitMarkdown = async () => {
  if (!form.markdown.trim()) {
    ElMessage.warning('请输入 Markdown 课件内容！')
    return
  }

  ElMessage.info('正在云端编译生成 PPT，预计需要 10~30 秒，请耐心等待...')

  // 把 markdown 字符串打包成临时 File 对象，触发编译
  // 把 Markdown 字符串包成 File 再走 FormData 上传：
    // 编译接口只接受文件流，不接受 JSON 文本字段。
  const blob = new Blob([form.markdown], { type: 'text/markdown' })
  const file = new File([blob], 'slidev-temp.md', { type: 'text/markdown' })

  const formData = new FormData()
  formData.append('file', file)

  const buildRes = await uploadMdCourseware(formData)
  if (buildRes.code !== 200) {
    ElMessage.error(buildRes.message || '课件编译失败')
    return
  }

  const pptUrl = buildRes.data
  const payload = {
    title: form.title,
    privacy: form.privacy,
    problemListId: form.problemListId || null,
    description: form.description,
    url: pptUrl,
    type: 0
  }

  const saveRes = await createCourseware(payload)
  if (saveRes.code === 200) {
    ElMessage.success('课件编译并发布成功！')
    setTimeout(() => window.close(), 1000)
  } else {
    ElMessage.error(saveRes.message || '数据库保存失败')
  }
}

// === Tab 2：腾讯文档嵌入链接直接录入 ===
const handleSubmitTencent = async () => {
  const payload = {
    title: form.title,
    privacy: form.privacy,
    problemListId: form.problemListId || null,
    description: form.description,
    url: form.url.trim(),
    type: 1
  }

  const saveRes = await createCourseware(payload)
  if (saveRes.code === 200) {
    ElMessage.success('课件创建成功！')
    setTimeout(() => window.close(), 1000)
  } else {
    ElMessage.error(saveRes.message || '保存失败')
  }
}
</script>

<style scoped>
.courseware-create-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: var(--bg-surface-hover);
  padding: 20px;
  box-sizing: border-box;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--bg-surface);
  padding: 15px 20px;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
  margin-bottom: 15px;
}

.page-header .title {
  font-size: 18px;
  font-weight: bold;
  color: var(--text-primary);
}

.form-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--bg-surface);
  padding: 20px;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
  min-height: 0;
}

.tencent-guide {
  margin-bottom: 18px;
  line-height: 1.8;
}

.tencent-form {
  max-width: 860px;
}

.editor-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin-top: 10px;
}

.editor-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-regular);
  margin-bottom: 10px;
}

.editor-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

:deep(.el-textarea) {
  flex: 1;
  display: flex;
  flex-direction: column;
}

/* 强制覆盖多行输入框的高度和样式，使其充满容器且可以独立滚动 */
:deep(.el-textarea__inner) {
  flex: 1;
  resize: none;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 14px;
  line-height: 1.6;
  padding: 15px;
  background-color: var(--bg-surface-hover);
}
</style>
