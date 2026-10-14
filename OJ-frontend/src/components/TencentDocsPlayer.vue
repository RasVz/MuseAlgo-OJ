<template>
  <div class="tencent-player">
    <div class="player-toolbar">
      <span>腾讯文档课件</span>
      <a :href="url" target="_blank" rel="noopener">新窗口打开/演示 ↗</a>
    </div>
    <iframe
      :src="url"
      class="player-iframe"
      frameborder="0"
      allow="fullscreen"
      title="腾讯文档课件"
    ></iframe>
    <div class="player-tip">无法显示？请确认该文档已在腾讯文档中开启「允许嵌入到其他网站」，或点击右上角新窗口打开。</div>
  </div>
</template>

<script setup>
/**
 * 腾讯文档课件播放器：以 iframe 嵌入外部文档链接。
 *
 * 用于课程学习页——课件地址是外部 http(s) 链接（而非站内 Slidev 课件）时，
 * 由课程学习页判断后切换到本组件渲染，而不是塞进普通 iframe。
 *
 * 本组件刻意做得很薄，因为腾讯文档的嵌入能力不在我们手里：
 * iframe 的加载成功与否受跨域限制，父组件拿不到任何 load/error 事件，
 * 无法像站内 iframe 那样判断「是否渲染完成」。
 * 因此这里把降级手段直接做进 UI：
 * 1. 顶部提供「新窗口打开」链接——文档未开启嵌入权限时这是唯一可用的查看方式；
 * 2. 底部常驻一行提示，说明需要文档作者开启「允许嵌入到其他网站」，
 *    把最常见的白屏原因写在用户眼前，而不是让用户对着空白 iframe 猜。
 *
 * 契约：url 必须是可直接嵌入的完整链接（required，不给默认值），
 * 由课程学习页先做完整性与协议校验再传进来；
 * 本组件不再追加任何防缓存参数——改写第三方文档 URL 会破坏其嵌入校验。
 */
defineProps({
  url: { type: String, required: true }
})
</script>

<style scoped>
.tencent-player {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 400px;
  overflow: hidden;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fff;
}
.player-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  color: #64748b;
  font-size: 12px;
  border-bottom: 1px solid #eef2f6;
  background: #f8fafc;
}
.player-toolbar a {
  color: #0d9488;
  font-weight: 600;
  text-decoration: none;
}
.player-toolbar a:hover {
  color: #065f46;
}
.player-iframe {
  flex: 1;
  width: 100%;
  min-height: 0;
  border: 0;
  background: #fff;
}
.player-tip {
  padding: 6px 14px;
  color: #909399;
  font-size: 12px;
  border-top: 1px solid #eef2f6;
  background: #fafafa;
}
</style>
