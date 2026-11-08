
import { showApiError } from '@/utils/errorFeedback'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import html2canvas from 'html2canvas'
import { reportDocument, safeReportSnapshot } from './reportIsolation'
import { reportExportStyles } from './reportExportStyles'

// Run report scripts only in an opaque-origin frame. A script-free, allowlisted snapshot is
// rendered inside an isolated shadow root for html2canvas; no report code runs in the parent.
export function useReportExport() {
  const exporting = ref(false)

  async function exportImage(htmlContent, studentName) {
    if (!htmlContent || exporting.value) return
    exporting.value = true
    let frame
    let host
    try {
      frame = document.createElement('iframe')
      frame.setAttribute('sandbox', 'allow-scripts')
      frame.style.cssText = 'position:fixed;left:-9999px;top:0;width:800px;height:800px;border:0;'
      frame.srcdoc = reportDocument(htmlContent)
      document.body.appendChild(frame)
      const snapshot = await requestSnapshot(frame)
      const clean = safeReportSnapshot(snapshot)
      clean.classList.add('report-snapshot')
      host = document.createElement('div')
      host.style.cssText = 'position:fixed;left:-9999px;top:0;width:800px;z-index:-1;'
      const shadow = host.attachShadow({ mode: 'open' })
      const style = document.createElement('style')
      style.textContent = reportExportStyles.replace(/\bbody\s*\{/g, '.report-snapshot {')
      shadow.append(style, clean)
      document.body.appendChild(host)
      const canvas = await html2canvas(clean, { scale: 2, backgroundColor: '#ffffff', width: 800, windowWidth: 800 })
      const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'))
      if (!blob) throw new Error('无法生成图片')
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = buildExportFileName(htmlContent, studentName)
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      ElMessage.success('图片导出成功')
    } catch (error) {
      showApiError(error, '导出图片失败: ' + (error?.message || '未知错误'))
    } finally {
      frame?.remove()
      host?.remove()
      exporting.value = false
    }
  }
  return { exporting, exportImage }
}

function requestSnapshot(frame) {
  const request = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
  return new Promise((resolve, reject) => {
    let interval
    let settled = false
    const finish = (error, value) => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      clearInterval(interval)
      window.removeEventListener('message', receive)
      frame.removeEventListener('load', send)
      if (error) reject(error)
      else resolve(value)
    }
    const receive = event => {
      if (event.source !== frame.contentWindow || event.data?.kind !== 'muse-report-snapshot' || event.data?.request !== request) return
      finish(null, event.data.html)
    }
    const timeout = setTimeout(() => finish(new Error('报告图表加载超时')), 10000)
    window.addEventListener('message', receive)
    const send = () => {
      if (!settled) frame.contentWindow?.postMessage({ kind: 'muse-report-request', action: 'snapshot', request }, '*')
    }
    frame.addEventListener('load', send, { once: true })
    interval = setInterval(send, 250)
  })
}

function buildExportFileName(htmlContent, studentName) {
  const name = String(studentName || '学生').replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 40) || '学生'
  const period = htmlContent.match(/(\d{4})-(\d{2})-(\d{2})\s*[～~]\s*(\d{4})-(\d{2})-(\d{2})/)
  return `${name}_学习周报${period ? `_${period[2]}${period[3]}_${period[5]}${period[6]}` : ''}.png`
}
