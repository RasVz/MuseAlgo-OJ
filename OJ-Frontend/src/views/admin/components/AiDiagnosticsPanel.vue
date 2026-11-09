<template>
  <el-card shadow="never" class="diagnostics-panel">
    <template #header><div class="heading"><strong>教学诊断与试跑</strong><el-button @click="load">刷新</el-button></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p>试跑不产生正式提交，次数单独统计；AI 请求与 token 见调用概览。统计保留 32 天。</p>
    <div class="metrics"><div v-for="item in metricLabels" :key="item[0]"><span>{{ item[1] }}</span><strong>{{ overview.metrics?.[item[0]] ?? '—' }}</strong></div></div>
    <el-form label-width="150px" inline>
      <el-form-item label="每次提问验证输入数"><el-input-number v-model="settings.maxCases" :min="0" :max="2" /></el-form-item>
      <el-form-item label="取证等待上限（秒）"><el-input-number v-model="settings.waitSeconds" :min="2" :max="30" /></el-form-item>
      <el-button :loading="saving" :disabled="!settingsLoaded" type="primary" @click="saveSettings">保存诊断预算</el-button>
    </el-form>
    <p>默认一个输入、一次取证轮；设为 0 暂停自动试跑，仍可依据题面和已有记录回答。</p>
    <el-table :data="overview.ranking || []" size="small"><el-table-column prop="userId" label="用户 ID" /><el-table-column prop="runs" label="试跑次数（含参考程序）" /></el-table>
    <div class="reference-query"><el-input v-model="problemId" placeholder="题目 ID" aria-label="查询参考题解的题目 ID" /><el-button :loading="loadingReference" @click="loadReference">查询参考题解</el-button></div>
    <div v-if="reference">
      <p role="status">{{ reference.available ? `已连通：${reference.title}（${reference.originalChars} 字符，${reference.truncated ? '头尾节选' : '完整材料'}）` : '未找到可用的公开官方题解，AI 将只依据题面、代码与运行事实分析' }}</p>
      <el-select v-model="selectedPost" aria-label="指定参考题解" style="width: min(400px, 100%)">
        <el-option label="自动选择公开官方题解" :value="0" />
        <el-option v-for="post in reference.candidates" :key="post.id" :label="`${post.id} · ${post.title}`" :value="Number(post.id)" />
      </el-select><el-button :loading="savingReference" @click="saveReference">保存题解选择</el-button>
      <p>仅可选择同题公开官方题解；教师私有参考代码只用于后台核验，不发送给模型或学生。</p>
    </div>
  </el-card>
</template>
<script setup>
import { ref, reactive, watch } from 'vue'
import request from '@/utils/request'
import { showApiError } from '@/utils/errorFeedback'
import { ElMessage } from 'element-plus'
const props = defineProps({ days: { type: Number, default: 7 } })
const overview = ref({}), settings = reactive({ maxCases: 1, waitSeconds: 12 }), settingsLoaded = ref(false), error = ref(''), saving = ref(false)
const reference = ref(null), problemId = ref(''), selectedPost = ref(0), loadingReference = ref(false), savingReference = ref(false)
let generation = 0, referenceGeneration = 0
const metricLabels = [['run.manual','手动试跑'],['run.auto','诊断试跑（含参考）'],['run.reused','复用记录'],['diagnosis.counterexamples','已验证反例'],['run.code-error','代码错误'],['run.timeout','运行超时'],['run.service-error','执行服务异常'],['run.elapsed-ms','执行总耗时 ms'],['reference.hit','题解命中'],['reference.miss','题解缺失'],['diagnosis.unavailable','取证未完成']]
const load = async () => {
  const version = ++generation
  try {
    const [stats, config] = await Promise.all([request({url:'/admin/ai/diagnostics',params:{days:props.days}}),request({url:'/admin/ai/diagnostics/settings'})])
    if (version !== generation) return
    overview.value = stats.data; Object.assign(settings,config.data); settingsLoaded.value = true; error.value = ''
  } catch (failure) { if (version === generation) error.value = failure.message || '诊断管理信息暂不可用' }
}
const saveSettings = async () => {
  saving.value = true
  try { await request({url:'/admin/ai/diagnostics/settings',method:'put',data:{...settings}}); ElMessage.success('诊断预算已保存') }
  catch (failure) { showApiError(failure,'保存失败，将重新读取实际配置') }
  finally { await load(); saving.value = false }
}
const loadReference = async () => {
  if (!/^[1-9]\d*$/.test(problemId.value) || !Number.isSafeInteger(Number(problemId.value))) return ElMessage.info('请输入有效题目 ID')
  const version = ++referenceGeneration, id = Number(problemId.value)
  loadingReference.value = true; reference.value = null
  try {
    const result = await request({url:'/admin/ai/diagnostics/reference',params:{problemId:id}})
    if (version !== referenceGeneration) return
    reference.value = result.data; selectedPost.value = Number(result.data.selectedPostId) || 0
  } catch (failure) { if (version === referenceGeneration) showApiError(failure,'参考题解查询失败') }
  finally { if (version === referenceGeneration) loadingReference.value = false }
}
const saveReference = async () => {
  const id = reference.value?.problemId
  if (!id) return
  savingReference.value = true
  try { await request({url:'/admin/ai/diagnostics/reference',method:'put',data:{problemId:id,postId:selectedPost.value || null}}); ElMessage.success('参考题解已保存'); if(Number(problemId.value) === id) await loadReference() }
  catch (failure) { showApiError(failure,'参考题解保存失败') }
  finally { savingReference.value = false }
}
watch(() => props.days, load, { immediate: true })
watch(problemId, () => { referenceGeneration++; reference.value = null; loadingReference.value = false })
</script>
<style scoped>
.diagnostics-panel { margin: 18px 0; }
.heading { display: flex; align-items: center; justify-content: space-between; }
p { font-size: 13px; color: var(--text-secondary); line-height: 1.6; }
.metrics { display: flex; flex-wrap: wrap; gap: 20px; margin: 18px 0; }.metrics > div { display: grid; gap: 8px; }.metrics span { font-size: 12px; color: var(--text-secondary); }.metrics strong { font-size: 22px; }
.reference-query { display: flex; gap: 10px; margin: 20px 0 10px; max-width: 400px; }
</style>
