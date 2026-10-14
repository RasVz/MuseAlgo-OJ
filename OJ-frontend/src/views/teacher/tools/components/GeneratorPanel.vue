<template>
  <div>
    <!-- 无 std 拦截 -->
    <el-alert v-if="!hasStd" type="error" :closable="false" class="mb12">
      <template #title>
        该题尚未保存标准答案，无法生成测试数据。
        <el-button type="primary" size="small" style="margin-left: 10px" @click="$emit('goto-std')">
          去生成标准答案
        </el-button>
      </template>
    </el-alert>

    <template v-else>
      <!-- 生成器代码 -->
      <div class="gen-head">
        <span class="gen-title">Python 生成器（AI 生成，可编辑后重新执行）</span>
        <div class="gen-actions">
          <el-button size="small" :loading="generating" @click="$emit('regenerate')">重新生成代码</el-button>
          <el-button size="small" type="primary" :loading="verifying" :disabled="!generatorCode" @click="$emit('verify')">
            执行生成并验证
          </el-button>
        </div>
      </div>
      <div v-loading="generating" class="mb12">
        <CodeBlockEditor
          v-if="generatorCode"
          :model-value="generatorCode"
          language="python"
          :height="220"
          @update:model-value="$emit('update:generatorCode', $event)"
        />
        <el-empty v-else description="正在生成生成器代码…" :image-size="60" />
      </div>

      <!-- 分布规划 -->
      <div v-if="plan.length" class="plan-block mb12">
        <div class="sub-title">数据分布规划（AI 按题面数据范围自动规划）</div>
        <el-table :data="plan" size="small" border>
          <el-table-column prop="group" label="分组" min-width="150" />
          <el-table-column prop="count" label="组数" width="80" align="center" />
          <el-table-column prop="desc" label="说明" min-width="260" show-overflow-tooltip />
        </el-table>
      </div>

      <!-- 验证结果 -->
      <div v-if="result">
        <el-alert
          :type="result.failed ? 'warning' : 'success'"
          :closable="false"
          class="mb12"
          :title="`生成 ${result.total} 组 · 通过 ${result.passed} 组 · 失败 ${result.failed} 组${result.failed ? '（失败组已丢弃，不追加）' : ''}`"
        />
        <div v-if="result.failedReasons.length" class="fail-list mb12">
          <div v-for="(r, i) in result.failedReasons" :key="i" class="fail-item">{{ r }}</div>
        </div>
      </div>

      <!-- 通过组预览 + 追加保存 -->
      <div v-if="passedCases.length" class="result-meta">
        <el-button size="small" type="primary" :loading="saving" @click="$emit('save')">
          追加保存 {{ passedCases.length }} 组
        </el-button>
        <span class="hint-text">追加不覆盖旧用例，重复输入自动跳过</span>
      </div>
      <el-alert v-if="savedMsg" :type="savedOk ? 'success' : 'warning'" :closable="false"
        :title="savedMsg" class="mb12" />

      <div v-if="passedCases.length" class="cases-grid">
        <div v-for="(c, i) in passedCases" :key="i" class="case-card">
          <div class="case-head">
            <span class="case-idx">#{{ i + 1 }}</span>
            <el-tag v-if="c.note" size="small" type="info" effect="plain">{{ c.note }}</el-tag>
            <el-button text size="small" @click="copyText(c.input, `用例 ${i + 1} 输入已复制`)">复制输入</el-button>
            <el-button text size="small" @click="copyText(c.output, `用例 ${i + 1} 输出已复制`)">复制输出</el-button>
          </div>
          <div class="case-col"><div class="case-col-title">输入</div><pre class="case-pre">{{ c.input }}</pre></div>
          <div class="case-col"><div class="case-col-title">输出（std 实测）</div><pre class="case-pre">{{ c.output }}</pre></div>
        </div>
      </div>
      <el-empty v-else-if="!generating && !verifying" description="点击「执行生成并验证」，系统会用标准答案逐组实测生成的数据" :image-size="70" />
    </template>
  </div>
</template>

<script setup>
// 测试数据生成器面板：展示 AI 生成的 Python 生成器、数据分布规划与验证结果。
// 纯展示组件，执行验证与追加保存的动作都上抛给 ProblemAiDialog。
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import CodeBlockEditor from './CodeBlockEditor.vue'

const props = defineProps({
  hasStd: { type: Boolean, default: false },
  generatorCode: { type: String, default: '' },
  plan: { type: Array, default: () => [] },
  generating: { type: Boolean, default: false },
  verifying: { type: Boolean, default: false },
  result: { type: Object, default: null },
  saving: { type: Boolean, default: false },
  savedMsg: { type: String, default: '' },
  savedOk: { type: Boolean, default: true }
})

defineEmits(['update:generatorCode', 'regenerate', 'verify', 'save', 'goto-std'])

// 后端只返回通过 std 实测的组：失败组在服务端就已丢弃，
// 前端不必（也不该）再过滤一次，避免两边口径不一致。
const passedCases = computed(() => (props.result?.cases) || [])

const copyText = async (text, msg) => {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(msg || '已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}
</script>

<style scoped>
.gen-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; flex-wrap: wrap; gap: 8px; }
.gen-title { font-weight: 600; font-size: 13.5px; color: var(--text-primary); }
.gen-actions { display: flex; gap: 8px; }
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-primary); margin-bottom: 8px; }
.plan-block { background: var(--bg-surface-soft); border: 1px solid var(--border-light); border-radius: 10px; padding: 12px; }
.fail-list { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 12px; max-height: 120px; overflow-y: auto; background: var(--bg-surface); }
.fail-item { font-size: 12px; color: var(--accent-danger); line-height: 1.9; word-break: break-all; }
.result-meta { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.hint-text { font-size: 12px; color: var(--text-secondary); }
.cases-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; max-height: 480px; overflow-y: auto; padding: 2px; }
.case-card { border: 1px solid var(--border-light); border-radius: 10px; padding: 10px 12px; background: var(--bg-surface); }
.case-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.case-idx { font-weight: 700; font-size: 12.5px; color: var(--accent-primary); background: var(--accent-primary-light); padding: 2px 8px; border-radius: 6px; }
.case-head .el-button { margin-left: auto; }
.case-col { margin-bottom: 6px; }
.case-col-title { font-size: 11px; color: var(--text-secondary); margin-bottom: 4px; }
.case-pre { margin: 0; background: var(--text-primary); color: var(--border-light); border-radius: 8px; padding: 8px 10px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; white-space: pre-wrap; word-break: break-all; max-height: 160px; overflow-y: auto; }
.mb12 { margin-bottom: 12px; }
</style>
