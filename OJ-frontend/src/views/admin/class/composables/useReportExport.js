import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import html2canvas from 'html2canvas'

/**
 * 学习周报 PNG 导出 composable。
 * 通过动态创建无 sandbox 的隐藏 iframe 渲染 HTML，
 * 等待 ECharts 完成后再用 html2canvas 截图下载。
 */
export function useReportExport() {
  const exporting = ref(false)

  /**
   * 导出当前报告为 PNG 图片。
   * @param {string} htmlContent 报告 HTML
   * @param {string} studentName 学生姓名（用于文件名）
   */
  async function exportImage(htmlContent, studentName) {
    if (!htmlContent) return
    exporting.value = true
    let shotIframe = null
    try {
      // 1. 构建文件名
      const fileName = buildExportFileName(htmlContent, studentName)

      // 2. 创建隐藏 iframe 渲染报告内容
      shotIframe = document.createElement('iframe')
      shotIframe.style.cssText = 'position:fixed;top:-9999px;left:-9999px;width:800px;height:auto;border:none;'
      shotIframe.srcdoc = htmlContent
      document.body.appendChild(shotIframe)

      // 3. 等待 iframe 加载完成 + ECharts 图表渲染（最多 10 秒）
      // 用 Promise.race 兜底超时：图表脚本若加载失败，waitForChartsReady 会永远不 resolve，
      // 没有超时就会卡在 exporting 状态且内存泄漏。
      await Promise.race([
        waitForChartsReady(shotIframe),
        new Promise(resolve => setTimeout(resolve, 10000))
      ])

      // 4. 额外等待确保图表动画完成
      await new Promise(resolve => setTimeout(resolve, 500))

      // 5. 截图
      const iframeDoc = shotIframe.contentDocument || shotIframe.contentWindow?.document
      if (!iframeDoc || !iframeDoc.body) {
        throw new Error('无法访问报告内容')
      }
      const canvas = await html2canvas(iframeDoc.body, {
        scale: 2,
        useCORS: true,
        allowTaint: true,
        backgroundColor: '#ffffff',
        width: 800,
        windowWidth: 800
      })

      // 6. 触发下载
      canvas.toBlob(blob => {
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = fileName
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(url)
        ElMessage.success('图片导出成功')
      }, 'image/png')
    } catch (e) {
      console.error('导出图片失败', e)
      ElMessage.error('导出图片失败: ' + (e?.message || '未知错误'))
    } finally {
      // iframe 必须移除：它带着 ECharts 实例与定时器留在文档里会持续占内存。
      if (shotIframe && shotIframe.parentNode) {
        document.body.removeChild(shotIframe)
      }
      exporting.value = false
    }
  }

  return { exporting, exportImage }
}

/**
 * 等待 iframe 内部 ECharts 图表渲染完成。
 * 依赖模板中的 #__charts_ready__ 标记（图表 finished 后 display:block）。
 */
function waitForChartsReady(iframe) {
  return new Promise((resolve) => {
    const check = () => {
      try {
        const doc = iframe.contentDocument || iframe.contentWindow?.document
        const marker = doc?.getElementById('__charts_ready__')
        if (marker && marker.style.display === 'block') {
          return resolve()
        }
      } catch { /* 跨域限制忽略 */ }
      setTimeout(check, 300)
    }
    // 先等 iframe 加载完成
    if (iframe.contentDocument?.readyState === 'complete') {
      setTimeout(check, 500)
    } else {
      iframe.addEventListener('load', () => setTimeout(check, 500), { once: true })
    }
  })
}

/**
 * 构建导出文件名：{学生姓名}_学习周报_{开始日期MMDD}_{结束日期MMDD}.png
 */
function buildExportFileName(htmlContent, studentName) {
  const name = studentName || '学生'

  // 从 HTML 标题中提取统计周期日期 (格式: 2024-12-01 ～ 2024-12-07)
  const periodMatch = htmlContent?.match(/(\d{4})-(\d{2})-(\d{2})\s*[～~]\s*(\d{4})-(\d{2})-(\d{2})/)
  let startStr = ''
  let endStr = ''
  if (periodMatch) {
    startStr = periodMatch[2] + periodMatch[3]  // MMDD
    endStr = periodMatch[5] + periodMatch[6]    // MMDD
  }
  const periodPart = startStr ? `_${startStr}_${endStr}` : ''

  return `${name}_学习周报${periodPart}.png`
}
