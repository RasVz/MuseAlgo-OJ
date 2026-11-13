<template>
  <div class="raw-view">
    <iframe
      ref="frameRef"
      :srcdoc="reportDocument(html)"
      class="report-iframe raw-iframe"
      title="学习周报预览"
      sandbox="allow-scripts allow-modals"
    />
  </div>
</template>

<script setup>
// 原始版是打印 / PNG / HTML 下载的权威视图：保留 opaque-origin iframe、
// CSP 与 postMessage 打印桥，正文脚本只在沙箱内执行。
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { reportDocument } from '@/views/admin/class/composables/reportIsolation'
import { showApiError } from '@/utils/errorFeedback'

defineProps({
  html: { type: String, default: '' }
})

const frameRef = ref(null)

function print() {
  const target = frameRef.value?.contentWindow
  if (!target) {
    ElMessage.warning('报告尚未加载完成，请稍后再试')
    return
  }
  try {
    target.postMessage({ kind: 'muse-report-request', action: 'print' }, '*')
  } catch (error) {
    showApiError(error, '打印失败，请改用「另存为 HTML」')
  }
}

defineExpose({ print })
</script>

<style scoped>
.raw-view { max-width: 1100px; margin: 0 auto; padding: 24px 24px 64px; }
.raw-iframe {
  display: block;
  width: 100%;
  height: calc(100vh - 170px);
  min-height: 640px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  background: #ffffff;
}
</style>
