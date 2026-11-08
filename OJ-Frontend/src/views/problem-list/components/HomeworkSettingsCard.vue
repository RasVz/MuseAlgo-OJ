<template>
  <el-card v-if="isHomeworkMode" shadow="always" class="homework-card">
    <template #header>
      <div class="section-title">作业设置</div>
    </template>

    <el-form label-width="96px" class="homework-form">
      <el-form-item label="发布状态">
        <el-radio-group v-model="form.privacy">
          <el-radio :value="0">草稿</el-radio>
          <el-radio :value="1">已发布</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="时间限制">
        <el-date-picker
          v-model="form.timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          format="YYYY-MM-DD HH:mm:ss"
          clearable
          style="width: 100%"
        />
      </el-form-item>

      <div class="homework-tips">
        不设置起止时间时，作业将视为长期有效。
      </div>
    </el-form>
  </el-card>
</template>

<script setup>
// 作业设置卡片：只维护「可见性 + 起止时间」两个字段，
// 通过 defineExpose 暴露取值方法给 ProblemListEditView 组装提交参数。
// 刻意不让它自己发请求——作业与题单共用一张表，保存入口必须统一在一处。
import { reactive, watch } from 'vue'

const props = defineProps({
  isHomeworkMode: { type: Boolean, default: false }
})

const form = reactive({
  privacy: 0,
  timeRange: []
})

// 时间在接口与前端控件之间的格式不一致：后端可能返回 ISO 的 "2024-01-01T10:00:00"，
// 也可能省略秒。这里统一补成 "YYYY-MM-DD HH:mm:ss"，否则保存时格式对不上会解析失败。
const normalizeDateTimeValue = (value) => {
  if (!value) return null
  if (typeof value !== 'string') return value

  const normalized = value.replace('T', ' ').trim()
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(normalized)) {
    return `${normalized}:00`
  }
  return normalized
}

/** 返回 [startTime, endTime]（未设置时为 [null, null]），供父组件提交时使用 */
const getTimeRangeForSubmit = () => {
  if (!Array.isArray(form.timeRange) || form.timeRange.length !== 2) {
    return [null, null]
  }
  return [
    normalizeDateTimeValue(form.timeRange[0]),
    normalizeDateTimeValue(form.timeRange[1])
  ]
}

// privacy 强制转数字：el-radio 传回的值可能是字符串，直接提交会让后端的 0/1 判定失效
const getPrivacy = () => Number(form.privacy ?? 0)

const reset = () => {
  form.privacy = 0
  form.timeRange = []
}

const syncFromDetail = (data) => {
  form.privacy = Number(data?.privacy ?? 0)
  form.timeRange = data?.startTime && data?.endTime
    ? [normalizeDateTimeValue(data.startTime), normalizeDateTimeValue(data.endTime)]
    : []
}

watch(
  () => props.isHomeworkMode,
  (val) => {
    if (val) reset()
  },
  { immediate: true }
)

defineExpose({ getTimeRangeForSubmit, getPrivacy, reset, syncFromDetail })
</script>

<style scoped>
.homework-card {
  margin-bottom: 20px;
  border-radius: 12px;
  border: none;
  box-shadow: var(--shadow-md) !important;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.homework-form {
  max-width: 720px;
}

.homework-tips {
  margin-left: 96px;
  margin-top: -6px;
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
