<template>
  <el-dialog
    v-model="dialogVisible"
    title="创建班级"
    width="480px"
    :close-on-click-modal="false"
  >
    <div class="dialog-tip">创建后可通过班级成员管理邀请学生加入</div>
    <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
      <el-form-item label="班级名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入班级名称" maxlength="40" show-word-limit />
      </el-form-item>
      <el-form-item label="所属学校">
        <el-input v-model="form.school" placeholder="选填" />
      </el-form-item>
      <el-form-item label="班级简介">
        <el-input v-model="form.description" type="textarea" :rows="3" placeholder="选填" />
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="handleCreate">
          立即创建
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/userStore'
import { createClass } from '@/api/class'

const props = defineProps({
  visible: { type: Boolean, default: false },
  teacherId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:visible', 'created'])

// v-model 的手动转发：Element Plus 弹窗关闭时要能通知父组件收起，
// 直接透传 props.visible 会让用户点遮罩关不掉弹窗。
const dialogVisible = ref(false)
watch(() => props.visible, (v) => { dialogVisible.value = v })
watch(dialogVisible, (v) => emit('update:visible', v))

const createLoading = ref(false)
const formRef = ref(null)

const form = reactive({
  name: '',
  school: '',
  description: ''
})

const formRules = reactive({
  name: [
    { required: true, message: '请输入班级名称', trigger: 'blur' },
    { min: 2, max: 40, message: '班级名称长度需在 2-40 个字符', trigger: 'blur' }
  ]
})

const handleCreate = async () => {
  if (!formRef.value) return
  await formRef.value.validate()

  createLoading.value = true
  try {
    const res = await createClass({
      name: form.name.trim(),
      school: form.school?.trim() || '',
      description: form.description?.trim() || '',
      // teacherId 允许管理员代建班级，因此优先用外部传入值而不是直接取 store。
      teacherId: props.teacherId ?? useUserStore().userId
    })
    if (res.code === 200) {
      ElMessage.success('班级创建成功')
      dialogVisible.value = false
      form.name = ''
      form.school = ''
      form.description = ''
      // 只通知不自行刷新：班级列表由父组件持有，组件内再查一次会造成数据源分裂。
      emit('created')
    } else {
      ElMessage.error(res.msg || res.message || '创建失败')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('创建失败，请稍后重试')
  } finally {
    createLoading.value = false
  }
}

watch(dialogVisible, (v) => {
  if (v) {
    nextTick(() => formRef.value?.clearValidate())
  }
})
</script>

<style scoped>
.dialog-tip {
  margin-bottom: 14px;
  font-size: 12px;
  color: var(--text-secondary);
}
.dialog-footer {
  width: 100%;
}
</style>
