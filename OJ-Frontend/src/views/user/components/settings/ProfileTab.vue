<template>
  <div class="tab-content">
    <h3 class="tab-title">个人信息</h3>

    <div class="avatar-section">
      <el-avatar :size="80" :src="localForm.avatar" class="setting-avatar" />
      <div class="avatar-actions">
        <div class="avatar-btn-row">
          <el-button type="primary" plain size="small" icon="Upload" :loading="avatarUploading" @click="triggerUpload">
            更换头像
          </el-button>
          <el-button size="small" icon="MagicStick" @click="$emit('random-avatar')">随机头像</el-button>
          <input
            ref="avatarInputRef"
            type="file"
            accept="image/png,image/jpeg"
            class="hidden-input"
            @change="$emit('avatar-change', $event)"
          >
        </div>
        <div class="avatar-tip">支持 JPG/PNG ≤2MB；随机头像由 DiceBear 本地生成，点击"保存修改"后生效</div>
      </div>
    </div>

    <el-form :model="localForm" label-position="top" class="setting-form">
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="昵称">
            <el-input v-model="localForm.nickname" maxlength="20" show-word-limit />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="真实姓名">
            <el-input v-model="localForm.realName" placeholder="仅用于比赛认证，对外不显示" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="个人简介">
        <el-input
          v-model="localForm.motto"
          type="textarea"
          :rows="3"
          maxlength="100"
          show-word-limit
          placeholder="一句话介绍你自己..."
        />
      </el-form-item>

      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="学校/机构">
            <el-input v-model="localForm.school" prefix-icon="School" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="专业/年级">
            <el-input v-model="localForm.major" prefix-icon="Reading" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item>
        <el-button type="primary" :loading="loading" @click="handleSave">保存修改</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

/**
 * 基本资料 Tab（展示组件）。
 * form 为 useUserSettings 持有的 reactive 对象；本地拷贝编辑（不直接改 props），
 * 保存时通过 save 事件携带最新表单载荷，由父组件合并回状态并落库。
 */
const props = defineProps({
  form: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  avatarUploading: { type: Boolean, default: false }
})

const emit = defineEmits(['save', 'random-avatar', 'avatar-change'])

// 浅拷贝在这里够用：profile 表单字段都是标量（昵称、学校、专业……），
// 唯一需要留意的是别把嵌套结构原样带出去。
const localForm = ref({ ...props.form })
// 父组件重置/回填（fillForm）时同步到本地
watch(() => props.form, (v) => {
  localForm.value = { ...v }
}, { deep: true })

const avatarInputRef = ref(null)
// 命令式触发隐藏的 file input：Element 的 upload 组件封装过重，
// 这里只需要"选一个文件"的最小能力。
const triggerUpload = () => {
  avatarInputRef.value?.click()
}

function handleSave() {
  emit('save', { ...localForm.value })
}
</script>

<style scoped>
.tab-content {
  padding: 0 40px 20px;
}
.tab-title {
  margin-top: 0;
  margin-bottom: 25px;
  font-size: 20px;
  color: var(--text-primary);
}
.avatar-section {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 30px;
}
.setting-avatar {
  border: 4px solid var(--bg-surface-hover);
}
.avatar-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.avatar-btn-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.setting-form {
  max-width: 600px;
}
.hidden-input {
  display: none;
}
</style>
