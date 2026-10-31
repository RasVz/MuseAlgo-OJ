<template>
  <aside class="article-toc" v-if="tocItems.length > 0">
    <nav class="toc-nav">
      <div class="toc-title">目录</div>
      <ul class="toc-list">
        <li
          v-for="(item, i) in tocItems"
          :key="i"
          :class="['toc-item', `toc-lv${item.level}`, { active: activeTocIndex === i }]"
          @click="scrollToHeading(item.id)"
        >
          {{ item.text }}
        </li>
      </ul>
    </nav>
  </aside>
</template>

<script setup>
// 文章目录侧栏：用 IntersectionObserver 跟随滚动高亮当前所在章节。
// 不用 scroll 事件 + getBoundingClientRect 逐个比对，是因为标题可能有几十个，
// 每次滚动都遍历一遍在长文里会明显掉帧。
import { ref, onMounted, onUnmounted, nextTick } from 'vue'

const props = defineProps({
  tocItems: { type: Array, default: () => [] }
})

const activeTocIndex = ref(-1)
let tocObserver = null

const scrollToHeading = (id) => {
  const el = document.getElementById(id)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// 重新建立前必须先 disconnect：IntersectionObserver 一旦建立就会持续持有对 DOM 的引用，
// 不解绑会在文章重渲染后监听已被替换的旧节点。
const setupTocObserver = () => {
  nextTick(() => {
    if (tocObserver) tocObserver.disconnect()
    const headings = document.querySelectorAll('.markdown-body h1, .markdown-body h2, .markdown-body h3')
    if (headings.length === 0) return
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const idx = props.tocItems.findIndex(t => t.id === entry.target.id)
            if (idx >= 0) activeTocIndex.value = idx
          }
        }
      },
      // rootMargin 把判定区域收成视口中间的一条窄带：
      // 上边距 -80px 避开吸顶导航，下边距 -60% 让"当前章节"以读者真正在读的位置为准，
      // 否则滚到底部时最后几个标题会同时被判定为可见，高亮来回跳。
      { rootMargin: '-80px 0px -60% 0px' }
    )
    headings.forEach(h => observer.observe(h))
    tocObserver = observer
  })
}

onMounted(setupTocObserver)
// 卸载时必须 disconnect：观察器不随组件销毁自动停止
onUnmounted(() => {
  if (tocObserver) tocObserver.disconnect()
})
</script>

<style scoped>
.article-toc {
  width: 220px; flex-shrink: 0;
  padding: 32px 20px 32px 0;
  border-left: 1px solid var(--border-light);
}

.toc-nav {
  position: sticky; top: 100px;
}

.toc-title {
  font-size: 13px; font-weight: 700; color: var(--text-secondary);
  text-transform: uppercase; letter-spacing: 0.5px;
  margin-bottom: 14px; padding-left: 12px;
}

.toc-list { list-style: none; margin: 0; padding: 0; }

.toc-item {
  font-size: 13px; color: var(--text-secondary); cursor: pointer;
  padding: 5px 12px; border-left: 2px solid transparent;
  transition: all 0.15s; line-height: 1.5;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.toc-item:hover { color: var(--text-primary); }

.toc-item.active {
  color: var(--accent-primary); font-weight: 500;
  border-left-color: var(--accent-primary);
  background: var(--accent-primary-light);
}

.toc-lv1 { padding-left: 12px; font-weight: 600; }
.toc-lv2 { padding-left: 24px; }
.toc-lv3 { padding-left: 36px; font-size: 12px; }
</style>
