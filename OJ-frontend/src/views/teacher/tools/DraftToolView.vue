<template>
  <div class="tool-page admin-scroll-page">
    <div class="page-header">
      <div class="page-title">题面生成</div>
      <div class="page-desc">输入命题要求，AI 生成完整题面（标题 / 描述 / 输入输出格式 / 样例 / 数据范围）</div>
    </div>

    <div class="panel">
      <el-form label-position="top">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="知识点">
              <el-select v-model="req.knowledgePoint" filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="k in knowledgePoints" :key="k" :label="k" :value="k" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="难度">
              <el-select v-model="req.difficulty" style="width: 100%">
                <el-option v-for="d in difficulties" :key="d" :label="d" :value="d" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="数据规模">
              <el-input v-model="req.scale" placeholder="如 n ≤ 10^5" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="场景设定（可选，留空由 AI 自由发挥）">
          <el-input v-model="req.scenario" type="textarea" :rows="2"
            placeholder="如：教室排队，按身高站队；小慕收集知识星…" />
        </el-form-item>
        <el-form-item label="附加要求（可选）">
          <el-input v-model="req.extra" type="textarea" :rows="2"
            placeholder="如：不要涉及位运算；输出带提示语；样例要 3 组…" />
        </el-form-item>
        <div class="foot">
          <el-switch v-model="req.ipMode" active-text="启用睿思星球世界观命名" />
          <span class="hint">开启后自动使用小慕 / 思思 / 睿思学院等原创 IP 元素</span>
          <el-button type="primary" size="large" class="gen-btn" :loading="generating" @click="generate">
            ✨ 生成题面
          </el-button>
        </div>
      </el-form>
    </div>

    <div v-if="statement.title" v-loading="generating" class="panel result">
      <div class="result-head">
        <div class="result-title">{{ statement.title }}</div>
        <div class="result-actions">
          <el-button size="small" @click="copyText(statementMarkdown, '题面已复制')">复制题面</el-button>
          <el-button size="small" type="primary" :loading="saving" @click="saveProblem">保存为私有题目</el-button>
        </div>
      </div>
      <div class="result-meta">
        <el-tag v-for="t in statement.tags" :key="t" size="small" type="primary" effect="light" class="tag">{{ t }}</el-tag>
        <span class="diff">{{ difficultyLabel }} · {{ statement.difficultyScore }} 分</span>
        <span class="samples">样例 {{ statement.samples.length }} 组</span>
      </div>
      <el-alert v-if="savedId" type="success" :closable="false" class="saved-tip"
        :title="`已保存为私有草稿 #${savedId}，可去题目编辑器继续完善`" />
      <el-divider />
      <div class="split">
        <div class="preview-col">
          <div class="sub-title">渲染预览</div>
          <div class="preview-card">
            <div class="markdown-body custom-md" v-html="renderMarkdown(statement.content)" />
            <template v-if="statement.inputDesc">
              <h4 class="sec">输入格式</h4>
              <div class="markdown-body custom-md" v-html="renderMarkdown(statement.inputDesc)" />
            </template>
            <template v-if="statement.outputDesc">
              <h4 class="sec">输出格式</h4>
              <div class="markdown-body custom-md" v-html="renderMarkdown(statement.outputDesc)" />
            </template>
            <template v-if="statement.samples.length">
              <h4 class="sec">样例</h4>
              <div v-for="(s, i) in statement.samples" :key="i" class="sample-block">
                <div class="sample-label">样例 {{ i + 1 }}</div>
                <div class="sample-row">
                  <div class="sample-col"><div class="sample-col-title">输入</div><pre class="sample-pre">{{ s.input }}</pre></div>
                  <div class="sample-col"><div class="sample-col-title">输出</div><pre class="sample-pre">{{ s.output }}</pre></div>
                </div>
                <div v-if="s.explanation" class="sample-exp">解释：{{ s.explanation }}</div>
              </div>
            </template>
            <template v-if="statement.hint">
              <h4 class="sec">提示</h4>
              <div class="markdown-body custom-md" v-html="renderMarkdown(statement.hint)" />
            </template>
          </div>
        </div>
        <div class="edit-col">
          <div class="sub-title">可编辑字段（修改实时生效）</div>
          <el-form label-position="top" size="small">
            <el-form-item label="标题"><el-input v-model="statement.title" /></el-form-item>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="难度分">
                  <el-input-number v-model="statement.difficultyScore" :min="800" :max="3000" :step="100" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="标签（逗号分隔）">
                  <el-input v-model="tagsText" placeholder="模拟,贪心" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="题目描述（Markdown，含数据范围）">
              <el-input v-model="statement.content" type="textarea" :rows="9" />
            </el-form-item>
            <el-form-item label="输入格式"><el-input v-model="statement.inputDesc" type="textarea" :rows="2" /></el-form-item>
            <el-form-item label="输出格式"><el-input v-model="statement.outputDesc" type="textarea" :rows="2" /></el-form-item>
            <el-form-item label="提示（可选）"><el-input v-model="statement.hint" type="textarea" :rows="2" /></el-form-item>
            <div class="sub-label">样例（{{ statement.samples.length }} 组）</div>
            <div v-for="(s, i) in statement.samples" :key="i" class="sample-edit">
              <div class="sample-edit-head">
                <span>样例 {{ i + 1 }}</span>
                <el-button text type="danger" size="small" @click="statement.samples.splice(i, 1)">删除</el-button>
              </div>
              <el-input v-model="s.input" type="textarea" :rows="2" placeholder="输入" />
              <el-input v-model="s.output" type="textarea" :rows="2" placeholder="输出" class="mt8" />
              <el-input v-model="s.explanation" placeholder="样例解释（可选）" class="mt8" />
            </div>
            <el-button size="small" @click="statement.samples.push({ input: '', output: '', explanation: '' })">+ 添加样例</el-button>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// AI 命题工具（教师）：输入命题要求与知识点，由 AI 生成题面并落库为题目。
// 生成的难度是"评分"而非"档位名"，展示时再按区间映射回档位。
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { createMarkdown } from '@/utils/markdown'
import { createProblem } from '@/api/problem'
import { wbStatement } from '@/api/teacherAi'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'

const md = createMarkdown({ html: false, math: true, breaks: true })
const renderMarkdown = (text) => (text ? md.render(text) : '')

const knowledgePoints = ['模拟', '枚举', '贪心', '二分查找', '动态规划', '排序', '数学', '高精度', '前缀和', '栈与队列', '递归', '图论入门', '字符串']
const difficulties = ['入门', '普及-', '普及', '普及+', '提高', '提高+', '省选']
// 难度档位 → 分值的映射。AI 可能返回自己的分值，也可能不给，
// 这张表是兜底基准（与题库的 800 起步评分体系一致）。
const DIFF_SCORE = { '入门': 800, '普及-': 1100, '普及': 1400, '普及+': 1700, '提高': 2000, '提高+': 2400, '省选': 2800 }

// 反向映射：按分数区间回推档位名展示。
// 注意区间与 DIFF_SCORE 的档位值并不完全对齐（如 1700 分显示为"普及+"而非"普及+"档），
// 这是有意的——分值是连续的，展示名分档更粗，便于快速判断。
const difficultyLabel = computed(() => {
  const s = statement.difficultyScore
  if (s >= 2600) return '提高+'
  if (s >= 2200) return '提高'
  if (s >= 1800) return '普及+'
  if (s >= 1500) return '普及'
  if (s >= 1200) return '普及-'
  return '入门'
})

const generating = ref(false)
const saving = ref(false)
const savedId = ref(null)
const tagsText = ref('')

const req = reactive({ knowledgePoint: '贪心', difficulty: '普及-', scenario: '', scale: 'n ≤ 10^5', extra: '', ipMode: true })
const statement = reactive({ title: '', content: '', inputDesc: '', outputDesc: '', hint: '', difficultyScore: 1100, tags: [], samples: [] })

// 完整题面导出：把分散的字段拼成一份可直接粘贴到别处的 Markdown，
// 样例必须用代码块包裹，否则多行输入会被 Markdown 折行破坏。
const statementMarkdown = computed(() => {
  let t = `# ${statement.title}\n\n${statement.content}\n`
  if (statement.inputDesc) t += `\n### 输入格式\n${statement.inputDesc}\n`
  if (statement.outputDesc) t += `\n### 输出格式\n${statement.outputDesc}\n`
  if (statement.hint) t += `\n### 提示\n${statement.hint}\n`
  statement.samples.forEach((s, i) => {
    t += `\n### 样例 ${i + 1}\n输入：\n\`\`\`\n${s.input}\n\`\`\`\n输出：\n\`\`\`\n${s.output}\n\`\`\``
    if (s.explanation) t += `\n解释：${s.explanation}`
  })
  return t
})

// 每次生成后清掉 savedId：上一次的题目 ID 与新题面无关，
// 继续显示会让教师误以为这次的内容已经存过。
const generate = async () => {
  if (!req.knowledgePoint) { ElMessage.warning('请选择知识点'); return }
  generating.value = true
  try {
    const res = await wbStatement({
      knowledgePoint: req.knowledgePoint, difficulty: req.difficulty,
      scenario: req.scenario, scale: req.scale, extra: req.extra,
      ipMode: req.ipMode ? 'on' : 'off'
    })
    if (!isResultSuccess(res)) { ElMessage.error(getResultMessage(res, '生成失败')); return }
    const json = JSON.parse(unwrapResultData(res) || '{}')
    statement.title = json.title || ''
    statement.content = json.content || ''
    statement.inputDesc = json.inputDesc || ''
    statement.outputDesc = json.outputDesc || ''
    statement.hint = json.hint || ''
    statement.difficultyScore = Number(json.difficultyScore) || DIFF_SCORE[req.difficulty] || 1100
    statement.tags = Array.isArray(json.tags) ? json.tags.map(String) : []
    tagsText.value = statement.tags.join(',')
    statement.samples = Array.isArray(json.samples) ? json.samples.map(s => ({
      input: s.input ?? '', output: s.output ?? '', explanation: s.explanation ?? ''
    })) : []
    savedId.value = null
    if (!statement.title) { ElMessage.error('生成内容为空，请重试'); return }
    ElMessage.success('题面生成完成，请人工复核')
  } catch (e) {
    console.error(e)
    ElMessage.error('生成失败，请稍后重试')
  } finally {
    generating.value = false
  }
}

// 一律保存为私有草稿（isPublic=0）：AI 生成的题面必须经教师复核、
// 补齐判题用例后才能公开，绝不能直接对学生可见。
const saveProblem = async () => {
  if (!statement.title || !statement.content) { ElMessage.warning('题面不完整'); return }
  saving.value = true
  try {
    const payload = {
      title: statement.title,
      content: statement.content,
      inputDesc: statement.inputDesc,
      outputDesc: statement.outputDesc,
      hint: statement.hint,
      difficulty: Number(statement.difficultyScore) || 1100,
      timeLimit: 1000,
      memoryLimit: 512,
      tags: tagsText.value,
      isPublic: 0,
      source: '睿思命题工坊',
      samples: statement.samples.map(s => ({ input: s.input || '', output: s.output || '' }))
    }
    const res = await createProblem(payload)
    if (!isResultSuccess(res)) { ElMessage.error(getResultMessage(res, '保存失败')); return }
    const id = Number(unwrapResultData(res))
    if (!id) { ElMessage.error('创建成功但未取到题目 ID，请刷新题库查看'); return }
    savedId.value = id
    ElMessage.success(`题目 #${id} 已保存为私有草稿`)
  } catch (e) {
    console.error(e)
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

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
.tool-page { padding: 20px; }
.page-header { margin-bottom: 18px; }
.page-title { font-size: 20px; font-weight: 700; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.panel { background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: 14px; padding: 20px 22px; margin-bottom: 16px; }
.foot { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.hint { font-size: 12px; color: var(--text-secondary); }
.gen-btn { margin-left: auto; }
.result-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.result-title { font-size: 19px; font-weight: 700; color: var(--text-primary); }
.result-actions { display: flex; gap: 8px; }
.result-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
.tag { margin-right: 2px; }
.diff { font-size: 12px; color: var(--accent-warning); }
.samples { font-size: 12px; color: var(--text-secondary); }
.saved-tip { margin-top: 12px; }
.split { display: grid; grid-template-columns: 1.1fr 1fr; gap: 18px; }
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-primary); margin-bottom: 10px; }
.preview-card { border: 1px solid var(--border-light); border-radius: 12px; padding: 18px 20px; background: var(--bg-surface); }
.sec { font-size: 14px; font-weight: 600; color: var(--text-primary); margin: 14px 0 6px; }
.sample-block { border: 1px dashed var(--border-base); border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; background: var(--bg-surface); }
.sample-label { font-size: 12px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; }
.sample-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.sample-col-title { font-size: 11px; color: var(--text-secondary); margin-bottom: 4px; }
.sample-pre { margin: 0; background: var(--bg-app); border-radius: 6px; padding: 8px 10px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; white-space: pre-wrap; word-break: break-all; color: var(--text-primary); }
.sample-exp { font-size: 12px; color: var(--text-secondary); margin-top: 6px; }
.sub-label { font-size: 12.5px; font-weight: 600; color: var(--text-primary); margin: 10px 0 8px; }
.sample-edit { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px; margin-bottom: 10px; }
.sample-edit-head { display: flex; justify-content: space-between; align-items: center; font-size: 12.5px; font-weight: 600; color: var(--text-secondary); }
.mt8 { margin-top: 8px; }
@media (max-width: 1100px) { .split { grid-template-columns: 1fr; } }
</style>
