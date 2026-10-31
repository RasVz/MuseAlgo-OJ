<template>
  <div class="form-container">
    <el-form 
      :model="formData" 
      label-position="top" 
      :rules="rules" 
      ref="formRef"
      size="large"
    >
      <el-row :gutter="40">
        <el-col :span="16">
          <el-form-item label="题单名称" prop="title">
            <el-input 
              v-model="formData.title" 
              placeholder="请输入具有辨识度的名称" 
              maxlength="30"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="可见性权限" prop="privacy">
            <el-select 
              v-model="formData.privacy" 
              placeholder="请选择可见性" 
              class="auth-select"
              style="width: 100%"
            >
              <template #prefix>
                <el-icon v-if="formData.privacy === 1" class="prefix-icon"><View /></el-icon>
                <el-icon v-else class="prefix-icon"><Lock /></el-icon>
              </template>
              
              <el-option :value="1" label="公开">
                <div class="auth-option-item">
                  <span class="item-main"><el-icon><View /></el-icon> 公开</span>
                  <span class="item-sub">所有人可见</span>
                </div>
              </el-option>
              
              <el-option :value="0" label="私有">
                <div class="auth-option-item">
                  <span class="item-main"><el-icon><Lock /></el-icon> 私有</span>
                  <span class="item-sub">仅自己可见</span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>
          
          <el-form-item label="题单简介" prop="description">
            <el-input 
              type="textarea" 
              v-model="formData.description" 
              :rows="8" 
              placeholder="支持 Markdown 格式，介绍一下这个题单的学习目标..."
              resize="none"
            />
          </el-form-item>
        </el-col>
        
        <el-col :span="8">
          <div class="side-panel">
            <div class="panel-header">
              <span class="panel-title">发布操作</span>
            </div>
            <div class="panel-content">
              <p class="panel-desc">
                更新后的信息将立即生效。请确保题单内容符合社区规范。
              </p>
              <el-divider border-style="dashed" />

              <div class="status-item">
                <span class="label">当前状态：</span>
                <el-tag :type="formData.privacy === 1 ? 'success' : 'info'" size="small" effect="plain">
                  {{ formData.privacy === 1 ? '公开展示' : '私有隐藏' }}
                </el-tag>
              </div>
              
              <div class="action-area">
                <el-button
                  v-if="!isCreateMode"
                  type="danger"
                  icon="Delete"
                  class="action-btn"
                  round
                  @click="$emit('delete')"
                  :disabled="saving"
                >
                  删除题单
                </el-button>
                <el-button 
                  type="primary" 
                  icon="Check"
                  @click="saveInfo" 
                  :loading="saving" 
                  class="action-btn"
                  round
                >
                  保存修改
                </el-button>
              </div>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-form>
  </div>
</template>

<script setup>
// 题单基本设置编辑：标题、可见性、简介的独立保存入口。
// 只更新这三个字段，不碰题目与顺序——题目管理由另一个子组件负责，
// 分开保存避免"改了简介顺手把题目顺序也提交一遍"。
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { View, Lock } from '@element-plus/icons-vue'
import { getProblemListDetail, updateProblemList } from '@/api/problemList'

const props = defineProps({
  listId: { type: String, required: true },
  isCreateMode: { type: Boolean, default: false }
})

// 标题显示在父页面的页头（编辑态是只读文本），保存后要通知父组件同步，
// 否则页头会一直显示旧标题。
const emit = defineEmits(['update-title', 'delete'])

const formRef = ref(null)
const saving = ref(false)
const formData = ref({
  title: '',
  description: '',
  privacy: 1
})

const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }]
}

// 初始化
const initData = async () => {
  try {
    const res = await getProblemListDetail(props.listId)
    formData.value = {
      title: res.data.title,
      description: res.data.description,
      privacy: res.data.privacy
    }
    emit('update-title', res.data.title)
  } catch {
    ElMessage.error('加载题单信息失败')
  }
}

// 保存失败时的提示由全局 axios 拦截器统一给出，这里不再重复弹。
const saveInfo = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (valid) {
      saving.value = true
      try {
        await updateProblemList({ 
          id: props.listId, 
          ...formData.value 
        })
        ElMessage.success('保存成功')
        emit('update-title', formData.value.title)
      } catch {
        ElMessage.error('保存失败')
      } finally {
        saving.value = false
      }
    }
  })
}

onMounted(() => {
  initData()
})
</script>

<style scoped>
.form-container { padding: 20px 10px; }
.auth-select { --el-select-input-focus-border-color: var(--accent-primary); }
.prefix-icon { margin-left: 2px; font-size: 16px; color: var(--text-regular); }
.auth-option-item { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.item-main { display: flex; align-items: center; font-weight: 500; gap: 5px; }
.item-sub { font-size: 12px; color: var(--text-secondary); }
.side-panel { background: var(--bg-surface-hover); padding: 20px; border-radius: 8px; border: 1px solid var(--border-light); height: 100%; box-sizing: border-box; display: flex; flex-direction: column; }
.panel-header { margin-bottom: 15px; }
.panel-title { font-weight: 600; color: var(--text-primary); font-size: 15px; }
.panel-content { flex: 1; display: flex; flex-direction: column; }
.panel-desc { font-size: 13px; color: var(--text-regular); line-height: 1.6; margin: 0; }
.status-item { display: flex; justify-content: space-between; align-items: center; font-size: 14px; }
.action-area { display: flex; flex-direction: column; align-items: center; gap: 12px; margin-top: auto; padding-top: 30px; }
.action-btn { width: 80%; height: 40px; font-weight: 600; margin-left: 0; }
.action-btn.el-button--primary { box-shadow: 0 4px 12px var(--el-color-primary-light-7); }
.action-btn.el-button--danger { box-shadow: 0 4px 12px var(--el-color-danger-light-7); }
</style>