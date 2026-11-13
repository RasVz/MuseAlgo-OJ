<template>
  <section v-if="samples.length" class="problem-samples" aria-labelledby="problem-samples-title">
    <h3 id="problem-samples-title" class="samples-title">样例</h3>
    <article v-for="(sample, index) in samples" :key="index" class="sample-item">
      <div class="sample-item-title">样例 {{ index + 1 }}</div>
      <div class="sample-grid">
        <div class="sample-block">
          <div class="sample-label">
            <span>输入</span>
            <button
              type="button"
              class="sample-copy"
              :aria-label="`复制样例 ${index + 1} 的输入`"
              @click="copy(sample.input, index, 'input')"
            >
              <el-icon><component :is="copiedKey === `${index}-input` ? Check : CopyDocument" /></el-icon>
              {{ copiedKey === `${index}-input` ? '已复制' : '复制' }}
            </button>
          </div>
          <pre :class="{ 'is-empty': sample.input === '' }">{{ sample.input === '' ? '（空）' : sample.input }}</pre>
        </div>
        <div class="sample-block">
          <div class="sample-label">
            <span>输出</span>
            <button
              type="button"
              class="sample-copy"
              :aria-label="`复制样例 ${index + 1} 的输出`"
              @click="copy(sample.output, index, 'output')"
            >
              <el-icon><component :is="copiedKey === `${index}-output` ? Check : CopyDocument" /></el-icon>
              {{ copiedKey === `${index}-output` ? '已复制' : '复制' }}
            </button>
          </div>
          <pre :class="{ 'is-empty': sample.output === '' }">{{ sample.output === '' ? '（空）' : sample.output }}</pre>
        </div>
      </div>
    </article>
  </section>
</template>

<script setup>
// 题面官方样例：只读展示，不提供编辑、新增或删除入口。
// 样例内容按纯文本插值渲染（不经过 Markdown/HTML），保留原始空格与换行。
import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, CopyDocument } from '@element-plus/icons-vue'

defineProps({
  samples: { type: Array, default: () => [] }
})

const copiedKey = ref('')
let copiedTimer = null

const writeClipboard = async text => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(area)
  if (!copied) throw new Error('copy failed')
}

const copy = async (text, index, part) => {
  try {
    await writeClipboard(text)
    copiedKey.value = `${index}-${part}`
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => {
      copiedKey.value = ''
    }, 1500)
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

onBeforeUnmount(() => {
  if (copiedTimer) clearTimeout(copiedTimer)
})
</script>

<style scoped>
.problem-samples {
  margin-top: 26px;
}
.samples-title {
  font-size: 16px;
  border-left: 4px solid var(--el-color-primary);
  padding-left: 10px;
  margin: 0 0 14px;
  color: var(--text-primary);
  font-weight: 600;
}
.sample-item {
  margin-bottom: 20px;
}
.sample-item:last-child {
  margin-bottom: 0;
}
.sample-item-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.sample-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.sample-block {
  min-width: 0;
  border: 1px solid var(--border-light);
  border-radius: 6px;
  overflow: hidden;
  background: var(--bg-surface);
}
.sample-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  background: var(--bg-input);
  border-bottom: 1px solid var(--border-light);
  font-size: 12px;
  color: var(--text-regular);
}
.sample-copy {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  border: 0;
  background: transparent;
  color: var(--accent-primary);
  font-size: 12px;
  cursor: pointer;
}
.sample-copy:hover {
  text-decoration: underline;
}
.sample-copy:focus-visible {
  outline: 2px solid var(--accent-primary);
  outline-offset: 1px;
  border-radius: 3px;
}
.sample-block pre {
  margin: 0;
  padding: 12px;
  max-height: 320px;
  overflow: auto;
  background: var(--bg-surface);
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre;
  tab-size: 4;
}
.sample-block pre.is-empty {
  color: var(--text-placeholder);
}
</style>
