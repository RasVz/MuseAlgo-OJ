<template>
  <el-form :model="localForm" ref="formRef" :rules="rules" label-position="top">
    <div class="panel-title">题目基础配置</div>

    <el-row :gutter="20">
      <el-col :span="16">
        <el-form-item label="题目名称" prop="title">
          <el-input v-model="localForm.title" placeholder="请输入题目名称" />
        </el-form-item>
      </el-col>
      <el-col :span="8">
        <el-form-item label="题目来源" prop="source">
          <el-input v-model="localForm.source" placeholder="如: LeetCode, NOIP2023" />
        </el-form-item>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="6">
        <el-form-item label="难度分" prop="difficulty">
          <el-input-number v-model="localForm.difficulty" :min="800" :max="3000" :step="100" style="width: 100%" />
        </el-form-item>
      </el-col>
      <el-col :span="6">
        <el-form-item label="时间限制 (ms)" prop="timeLimit">
          <el-input-number v-model="localForm.timeLimit" :min="100" :step="100" style="width: 100%" />
        </el-form-item>
      </el-col>
      <el-col :span="6">
        <el-form-item label="空间限制 (MB)" prop="memoryLimit">
          <el-input-number v-model="localForm.memoryLimit" :min="16" :step="16" style="width: 100%" />
        </el-form-item>
      </el-col>
      <el-col :span="6">
        <el-form-item label="可见性">
          <el-radio-group v-model="localForm.isPublic">
            <el-radio :label="1">公开</el-radio>
            <el-radio :label="0">私有</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-col>
    </el-row>

    <el-form-item label="标签" prop="tags">
      <el-input v-model="localForm.tags" placeholder="多个标签用逗号分隔，如: 动态规划,数组" />
    </el-form-item>

    <el-form-item label="题目内容 (Markdown)" prop="content">
      <el-input
        v-model="localForm.content"
        type="textarea"
        :rows="8"
        placeholder="请输入题目描述，支持 Markdown 格式"
      />
    </el-form-item>

    <el-row :gutter="20">
      <el-col :span="12">
        <el-form-item label="输入描述">
          <el-input v-model="localForm.inputDesc" type="textarea" :rows="4" placeholder="格式描述..." />
        </el-form-item>
      </el-col>
      <el-col :span="12">
        <el-form-item label="输出描述">
          <el-input v-model="localForm.outputDesc" type="textarea" :rows="4" placeholder="格式描述..." />
        </el-form-item>
      </el-col>
    </el-row>

    <div class="section-title">样例数据</div>
    <div
      v-for="(sample, index) in localForm.samples"
      :key="index"
      class="sample-editor-card"
    >
      <div class="sample-editor-header">
        <span class="sample-editor-title">样例 #{{ index + 1 }}</span>
        <div class="sample-editor-actions">
          <el-button link type="primary" size="small" :disabled="index === 0" @click="moveSample(index, -1)">
            上移
          </el-button>
          <el-button
            link
            type="primary"
            size="small"
            :disabled="index === localForm.samples.length - 1"
            @click="moveSample(index, 1)"
          >
            下移
          </el-button>
          <el-button link type="danger" size="small" @click="removeSample(index)">删除</el-button>
        </div>
      </div>
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="样例输入">
            <el-input
              v-model="sample.input"
              type="textarea"
              :rows="5"
              placeholder="输入样例..."
              class="code-font"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="样例输出">
            <el-input
              v-model="sample.output"
              type="textarea"
              :rows="5"
              placeholder="输出样例..."
              class="code-font"
            />
          </el-form-item>
        </el-col>
      </el-row>
    </div>
    <el-button type="primary" plain size="small" :icon="Plus" @click="addSample">添加样例</el-button>

    <el-form-item label="提示 (Hint)">
      <el-input v-model="localForm.hint" type="textarea" :rows="3" placeholder="给学生的解题提示..." />
    </el-form-item>
  </el-form>
</template>

<script setup>
// 题目基本信息表单：标题、题面、输入输出格式、样例、提示、难度、时限与可见性。
//
// 双向同步靠「本地副本 + 双向 watch + 深比较」实现：
// 直接改 props.form 会让父组件无法控制赋值时机（如 AI 格式化后整体覆盖），
// 而两个方向的 watch 若不做深比较会互相触发形成回环。
import { ref, watch } from 'vue'
import { Plus } from '@element-plus/icons-vue'

const props = defineProps({
  form: { type: Object, required: true },
  rules: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:form'])

// 本地拷贝编辑（不直接改 props）；编辑结果实时回传父组件，父组件回填时经 watch 同步回本地
const localForm = ref(structuredClone(props.form))
// 父组件 → 本地：接口回填或 AI 格式化后把值灌进来
watch(() => props.form, (v) => {
  localForm.value = structuredClone(v)
}, { deep: true })
// 本地 → 父组件：内容真有变化才 emit，否则父子互相赋值会无限循环
watch(localForm, () => {
  const next = structuredClone(localForm.value)
  if (JSON.stringify(next) === JSON.stringify(props.form)) return // 父组件同步来的，不回传
  emit('update:form', next)
}, { deep: true })

const formRef = ref(null)

const addSample = () => {
  localForm.value.samples.push({ input: '', output: '' })
}

const removeSample = (index) => {
  localForm.value.samples.splice(index, 1)
}

const moveSample = (index, delta) => {
  const target = index + delta
  if (target < 0 || target >= localForm.value.samples.length) return
  const current = localForm.value.samples[index]
  localForm.value.samples[index] = localForm.value.samples[target]
  localForm.value.samples[target] = current
}

defineExpose({ validate: () => formRef.value?.validate() })
</script>

<style scoped>
.panel-title {
  font-size: 16px;
  color: var(--text-primary);
  font-weight: 700;
  margin-bottom: 16px;
}

.section-title {
  font-weight: 700;
  margin: 8px 0 10px;
  color: var(--text-primary);
  font-size: 14px;
}

.sample-editor-card {
  border: 1px solid var(--border-light);
  border-radius: 8px;
  padding: 10px 14px 0;
  margin-bottom: 12px;
  background: var(--bg-surface);
}

.sample-editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.sample-editor-title {
  font-weight: 700;
  color: var(--text-primary);
  font-size: 13px;
}

.sample-editor-actions {
  display: flex;
  align-items: center;
}

.code-font :deep(.el-textarea__inner) {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  background-color: var(--bg-surface);
}
</style>
