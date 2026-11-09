<template>
  <section class="test-case-panel" aria-label="样例测试区">
    <div class="case-heading"><strong>样例与自定义测试</strong><el-button size="small" @click="openEditor()">新增样例</el-button></div>
    <div class="case-tabs">
      <el-button v-for="(item, index) in cases" :key="item.id" size="small" :type="item.id === model?.selectedId ? 'primary' : 'default'"
        @click="model.select(item.id)">样例 {{ index + 1 }}<span class="case-source">{{ sourceLabel(item.source) }}</span></el-button>
    </div>
    <div v-if="selected" class="case-detail">
      <div class="case-actions">
        <el-button type="primary" size="small" :loading="running" @click="emit('run', selected)">运行当前样例</el-button>
        <el-button size="small" :disabled="running || !cases.length" @click="emit('run-all')">运行全部样例</el-button>
        <el-button v-if="running" size="small" @click="emit('stop')">停止后续运行</el-button>
        <el-button v-if="selected.source !== 'OFFICIAL'" size="small" :disabled="running" @click="openEditor(selected)">编辑样例</el-button>
        <el-button v-if="selected.source !== 'OFFICIAL'" size="small" :disabled="running" @click="model.remove(selected.id)">删除样例</el-button>
      </div>
      <p v-if="selected.note" class="case-note">{{ selected.note }}</p>
      <div class="case-columns"><div><span>输入</span><pre>{{ selected.input || '（空输入）' }}</pre></div>
        <div><span>期望输出<span v-if="selected.source === 'AI_VERIFIED'"> · {{ expectedLabel }}</span></span><pre>{{ selected.hasExpected ? (selected.expectedOutput || '（空输出）') : '未设置' }}</pre></div></div>
      <div v-if="result" class="case-result" role="status">
        <strong>{{ resultLabel }}</strong><pre>{{ result.actual || result.stderr || result.compile_output || result.message || '（程序无输出）' }}</pre>
        <small v-if="result.time != null">耗时 {{ result.time }} 秒</small>
      </div>
      <p v-else class="case-note">{{ snapshot?.code ? '此版本尚未运行，或代码/样例已修改。' : '进入编辑器后可运行；新增样例仅在当前页面保留。' }}</p>
    </div>
    <p v-else class="case-note">点击新增样例，输入可以留空。</p>
    <el-dialog v-model="editing" :title="editingId ? '编辑临时样例' : '新增临时样例'" width="min(640px, 94vw)" append-to-body>
      <label for="case-input">样例输入</label><el-input id="case-input" v-model="form.input" type="textarea" :rows="4" :maxlength="4096" />
      <el-checkbox v-model="form.hasExpected">设置期望输出（可以为空字符串）</el-checkbox>
      <template v-if="form.hasExpected"><label for="case-expected">期望输出</label><el-input id="case-expected" v-model="form.expectedOutput" type="textarea" :rows="3" :maxlength="4096" /></template>
      <label for="case-note">备注</label><el-input id="case-note" v-model="form.note" :maxlength="200" />
      <template #footer><el-button @click="editing = false">取消</el-button><el-button type="primary" @click="save">保存到当前页面</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup>
import { computed, ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
const props = defineProps({ cases: { type: Array, default: () => [] }, model: { type: Object, default: null }, snapshot: { type: Object, default: null }, running: Boolean })
const emit = defineEmits(['run','run-all','stop'])
const selected = computed(() => props.cases.find(item => item.id === props.model?.selectedId))
const result = computed(() => { const item = props.cases.find(value => value.id === props.model?.selectedId); return item && props.snapshot ? props.model.result(item.id, props.snapshot) : null })
const resultLabel = computed(() => result.value?.truncated ? '输出已截断，无法完整比较' : [13,14].includes(Number(result.value?.status)) ? '运行服务异常，请稍后重试' : Number(result.value?.status) !== 3 ? ({5:'运行超时',6:'编译错误',7:'运行错误',8:'运行错误',9:'运行错误',10:'运行错误',11:'运行错误',12:'运行错误'}[Number(result.value?.status)] || '运行未完成')
  : result.value.matches === null ? '运行完成，未比较期望输出' : result.value.matches ? '输出匹配' : '输出不匹配')
const expectedLabel = '来自已验证反例'
const sourceLabel = source => ({ OFFICIAL: '官方', CUSTOM: '自定义', AI_VERIFIED: 'AI 实测' }[source] || source)
const editing = ref(false), editingId = ref(null)
const form = reactive({ input: '', expectedOutput: '', hasExpected: false, note: '' })
const openEditor = item => { editingId.value = item?.id ?? null; Object.assign(form, { input: item?.input ?? '', expectedOutput: item?.expectedOutput ?? '', hasExpected: item?.hasExpected ?? false, note: item?.note ?? '' }); editing.value = true }
const save = () => {
  try { if (editingId.value) props.model.edit(editingId.value, { ...form }); else props.model.add({ ...form }); editing.value = false }
  catch (error) { ElMessage.error(error.message) }
}
</script>
<style scoped>
.test-case-panel { padding: 14px; border: 1px solid var(--border-light); border-radius: 8px; background: var(--bg-surface); }
.case-heading,.case-actions,.case-tabs { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.case-heading { justify-content: space-between; margin-bottom: 10px; }
.case-tabs { margin-bottom: 12px; }
.case-tabs :deep(.el-button + .el-button) { margin-left: 0; }
.case-source { margin-left: 6px; font-size: 11px; opacity: .75; }
.case-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
.case-columns > div { min-width: 0; }
.case-columns span, .case-note, small { color: var(--text-secondary); font-size: 12px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 160px; overflow-y: auto; background: var(--bg-app); padding: 10px; font-family: monospace; }
.case-result { border-top: 1px solid var(--border-light); padding-top: 12px; }
label { display: block; margin: 12px 0 6px; }
@media (max-width: 600px) { .case-columns { grid-template-columns: 1fr; } }
</style>
