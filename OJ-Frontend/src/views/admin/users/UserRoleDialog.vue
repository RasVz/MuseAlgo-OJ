<template>
  <el-dialog
    :model-value="modelValue"
    :title="batchCount ? '批量调整角色' : '调整角色'"
    width="460px"
    :close-on-click-modal="false"
    @update:model-value="(value) => emit('update:modelValue', value)"
  >
    <p class="dialog-lead">
      <template v-if="batchCount">将选中的 {{ batchCount }} 个用户统一调整为：</template>
      <template v-else>
        将用户「{{ targetName }}」从「{{ roleLabel(currentRole) }}」调整为：
      </template>
    </p>
    <el-radio-group v-model="selectedRole" class="role-options">
      <el-radio :value="1" :disabled="isSelf">学生</el-radio>
      <el-radio :value="2" :disabled="isSelf">教师</el-radio>
      <el-radio :value="3">管理员</el-radio>
    </el-radio-group>
    <el-alert
      v-if="isSelf"
      type="warning"
      :closable="false"
      title="不能降低自己的管理员角色；如需调整请由其他管理员操作。"
    />
    <el-alert
      v-else
      type="info"
      :closable="false"
      title="角色变更会立即生效，目标用户正在使用的权限会同步变化。"
    />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!selectedRole" :loading="saving" @click="emit('confirm', selectedRole)">
        确认调整
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { roleLabel } from '@/utils/adminUserQuery'

const props = defineProps({
  modelValue: Boolean,
  saving: Boolean,
  targetName: { type: String, default: '' },
  currentRole: { type: Number, default: 1 },
  batchCount: { type: Number, default: 0 },
  isSelf: Boolean,
})
const emit = defineEmits(['update:modelValue', 'confirm'])
const selectedRole = ref(null)

watch(
  () => [props.modelValue, props.currentRole, props.batchCount],
  ([visible]) => {
    if (!visible) return
    selectedRole.value = props.batchCount ? null : props.currentRole || 1
  },
  { immediate: true },
)
</script>

<style scoped>
.dialog-lead {
  margin: 0 0 14px;
  color: var(--text-regular);
  font-size: 14px;
  line-height: 1.6;
}
.role-options {
  display: flex;
  gap: 18px;
  margin-bottom: 14px;
}
</style>
