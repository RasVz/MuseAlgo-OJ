<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="480px"
    :close-on-click-modal="false"
    @update:model-value="(value) => emit('update:modelValue', value)"
  >
    <p class="dialog-lead">
      <template v-if="batchCount">将对选中的 {{ batchCount }} 个用户执行此操作。</template>
      <template v-else>将对用户「{{ targetName }}」执行此操作。</template>
    </p>

    <template v-if="banning">
      <el-form label-width="82px">
        <el-form-item label="封禁原因">
          <el-input
            v-model="reason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="选填，记录在审计日志与用户详情中"
          />
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker
            v-model="expireTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空表示永久封禁"
            :disabled-date="disablePastDate"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        title="封禁生效后，目标用户的登录与已有 token 会立即被拒绝；到期后自动恢复。"
      />
    </template>
    <el-alert
      v-else
      type="info"
      :closable="false"
      title="启用后目标用户可以重新登录，已有封禁原因与到期时间会被清除。"
    />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button :type="banning ? 'danger' : 'primary'" :loading="saving" @click="submit">
        {{ banning ? '确认禁用' : '确认启用' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: Boolean,
  saving: Boolean,
  banning: Boolean,
  batchCount: { type: Number, default: 0 },
  targetName: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const reason = ref('')
const expireTime = ref('')

const dialogTitle = computed(() => {
  if (props.batchCount) return props.banning ? '批量禁用账号' : '批量启用账号'
  return props.banning ? '禁用账号' : '启用账号'
})

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    reason.value = ''
    expireTime.value = ''
  },
)

function disablePastDate(date) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return date.getTime() < today.getTime()
}

function submit() {
  if (props.banning && expireTime.value && new Date(expireTime.value).getTime() <= Date.now()) {
    ElMessage.warning('封禁到期时间必须晚于当前时间')
    return
  }
  emit('confirm', {
    reason: props.banning ? reason.value.trim() : '',
    expireTime: props.banning ? expireTime.value || null : null,
  })
}
</script>

<style scoped>
.dialog-lead {
  margin: 0 0 14px;
  color: var(--text-regular);
  font-size: 14px;
}
</style>
