<template>
  <el-dialog 
    v-model="visible" 
    :title="mode === 'create' ? '录入新课件' : '编辑课件信息'" 
    width="600px"
    append-to-body
    :close-on-click-modal="false"
  >
    <el-form :model="form" ref="formRef" :rules="rules" label-width="100px">
      <el-form-item label="课件标题" prop="title">
        <el-input v-model="form.title" placeholder="请输入课件标题" />
      </el-form-item>

      <el-form-item label="可见性">
        <el-radio-group v-model="form.privacy">
          <el-radio :label="1">公开共享 (所有老师可见)</el-radio>
          <el-radio :label="0">私有 (仅自己可见)</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="关联题单ID">
        <el-input v-model="form.problemListId" placeholder="选填，关联后学习页侧边栏将显示题目" style="width: 220px"/>
      </el-form-item>

      <el-form-item label="简介备注">
        <el-input v-model="form.description" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>
    
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="handleSubmit" :loading="submitting">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 课件新建/编辑弹窗：同一个弹窗承担两种模式，由 open(type, row) 的 type 区分。
// 这里只登记课件元信息（标题、路径、公开性、关联题单），
// Slidev 课件的编译与上传走独立的 CoursewareEditorView。
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { createCourseware, updateCourseware } from '@/api/courseware'

// 定义事件：操作成功后通知父组件刷新
const emit = defineEmits(['success'])

const visible = ref(false)
const mode = ref('create') // 'create' | 'edit'
const submitting = ref(false)
const formRef = ref(null)

// 表单数据初始化
const form = reactive({
  id: null,
  title: '',
  url: '',
  privacy: 1,
  problemListId: '',
  description: ''
})

const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  url: [{ required: true, message: '请输入路径', trigger: 'blur' }]
}

// open() 由父组件通过 ref 调用（命令式而非 v-model）：
// 「新建」与「编辑」需要不同参数，用事件表达反而绕。
// === 公开方法：供父组件调用 ===
const open = (type, row) => {
  mode.value = type
  visible.value = true
  
  // 重置表单状态
  if (formRef.value) formRef.value.resetFields()

  if (type === 'create') {
    form.id = null
    form.title = ''
    form.url = ''
    form.privacy = 1
    form.problemListId = ''
    form.description = ''
  } else {
    // 编辑模式回填数据
    form.id = row.id
    form.title = row.title
    form.url = row.url
    form.privacy = row.privacy
    form.problemListId = row.problemListId
    form.description = row.description
  }
}

// 提交逻辑
const handleSubmit = async () => {
  if (!formRef.value) return
  
  await formRef.value.validate(async (valid) => {
    if (valid) {
      submitting.value = true
      try {
        const payload = { ...form }
        // 处理空字符串为 null
        if (!payload.problemListId) payload.problemListId = null
        
        const api = mode.value === 'create' ? createCourseware : updateCourseware
        const res = await api(payload)
        
        if (res.code === 200) {
          ElMessage.success('操作成功')
          visible.value = false
          emit('success') // 通知父组件
        }
      } catch (e) {
        console.error(e)
      } finally {
        submitting.value = false
      }
    }
  })
}

// 暴露 open 方法给父组件
defineExpose({ open })
</script>

<style scoped>
</style>