<template>
  <div class="settings-tab">
    <h2 class="settings-tab-title">基本资料</h2>
    <p class="settings-tab-note">头像与个人资料会展示在个人主页、榜单和评论中。</p>

    <SettingsSection
      title="头像"
      description="支持 JPG / PNG ≤2MB；上传后立即生效，随机头像需点击「保存修改」后生效。"
    >
      <div class="avatar-editor">
        <el-avatar :size="80" :src="localForm.avatar || defaultAvatar" class="avatar-preview" />
        <div class="avatar-buttons">
          <el-button type="primary" plain :loading="avatarUploading" @click="triggerUpload">
            更换头像
          </el-button>
          <el-button @click="$emit('random-avatar')">随机头像</el-button>
          <input
            ref="avatarInputRef"
            type="file"
            accept="image/png,image/jpeg"
            class="hidden-input"
            aria-label="选择头像图片"
            @change="$emit('avatar-change', $event)"
          >
        </div>
      </div>
    </SettingsSection>

    <SettingsSection
      title="个人信息"
      description="修改后点击本分区的「保存修改」，其他分区不受影响。"
      :dirty="isDirty"
    >
      <template #actions>
        <el-button v-if="isDirty" text @click="resetSection">取消</el-button>
        <el-button type="primary" :loading="loading" @click="handleSave">保存修改</el-button>
      </template>

      <el-form
        :model="localForm"
        :label-position="labelPosition"
        :label-width="labelWidth"
        class="settings-form"
      >
        <SettingsRow title="昵称" description="展示在个人主页、榜单与评论中">
          <template #default="{ describedBy }">
            <el-input
              v-model="localForm.nickname"
              maxlength="20"
              show-word-limit
              :aria-describedby="describedBy"
              class="settings-input"
            />
          </template>
        </SettingsRow>

        <SettingsRow title="真实姓名" description="仅用于比赛认证，对外不显示">
          <template #default="{ describedBy }">
            <el-input
              v-model="localForm.realName"
              :aria-describedby="describedBy"
              class="settings-input"
            />
          </template>
        </SettingsRow>

        <SettingsRow title="个人简介" description="一句话介绍你自己，最多 100 字">
          <template #default="{ describedBy }">
            <el-input
              v-model="localForm.motto"
              type="textarea"
              :rows="3"
              maxlength="100"
              show-word-limit
              :aria-describedby="describedBy"
              class="settings-input"
            />
          </template>
        </SettingsRow>

        <SettingsRow title="学校 / 机构">
          <el-input v-model="localForm.school" class="settings-input" />
        </SettingsRow>

        <SettingsRow title="专业 / 年级">
          <el-input v-model="localForm.major" class="settings-input" />
        </SettingsRow>
      </el-form>
    </SettingsSection>
  </div>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import SettingsRow from './SettingsRow.vue'
import SettingsSection from './SettingsSection.vue'
import { getDefaultAvatar } from '@/utils/dicebear'

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

const narrow = inject('settingsNarrow', ref(false))
const labelPosition = computed(() => (narrow.value ? 'top' : 'left'))
const labelWidth = computed(() => (narrow.value ? 'auto' : '220px'))

// 浅拷贝在这里够用：profile 表单字段都是标量（昵称、学校、专业……）。
const editableKeys = ['nickname', 'realName', 'motto', 'school', 'major']
const localForm = ref({ ...props.form })
const baseline = ref({ ...props.form })
// 父组件回填（fillForm）/保存成功后同步草稿与基线
watch(() => props.form, (v) => {
  localForm.value = { ...v }
  baseline.value = { ...v }
}, { deep: true })

// 头像走独立的即时上传流程，不纳入个人信息分区的脏状态比较：
// 上传成功时父组件会更新 form.avatar，若把它算进来会误报「未保存」。
const isDirty = computed(() =>
  editableKeys.some((key) => (localForm.value[key] ?? '') !== (baseline.value[key] ?? ''))
)

function resetSection() {
  const next = { ...localForm.value }
  editableKeys.forEach((key) => {
    next[key] = baseline.value[key] ?? ''
  })
  localForm.value = next
}

const defaultAvatar = getDefaultAvatar()
const avatarInputRef = ref(null)
// 命令式触发隐藏的 file input：Element 的 upload 组件封装过重，
// 这里只需要「选一个文件」的最小能力。
const triggerUpload = () => {
  avatarInputRef.value?.click()
}

function handleSave() {
  emit('save', { ...localForm.value })
  // 提交即视为新基线；失败时由共享反馈层提示，用户可再次保存。
  baseline.value = { ...localForm.value }
}
</script>

<style scoped>
.settings-tab-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
}
.settings-tab-note {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-regular);
}
.avatar-editor {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}
.avatar-preview {
  border: 4px solid var(--bg-surface-hover);
}
.avatar-buttons {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.hidden-input {
  display: none;
}
.settings-input {
  width: min(100%, 420px);
}
</style>
