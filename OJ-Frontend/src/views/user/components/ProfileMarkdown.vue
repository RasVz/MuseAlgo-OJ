<template>
  <div class="profile-prose" v-html="html"></div>
</template>
<script setup>
import { computed } from 'vue'
import { renderProfileMarkdown } from '@/utils/profileMarkdown'
const props = defineProps({ source: { type: String, default: '' } })
const html = computed(() => renderProfileMarkdown(props.source))
</script>
<style scoped>
.profile-prose {
  color: var(--text-regular);
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
  min-width: 0;
}
.profile-prose :deep(> :first-child) {
  margin-top: 0;
}
.profile-prose :deep(> :last-child) {
  margin-bottom: 0;
}
.profile-prose :deep(h1),
.profile-prose :deep(h2),
.profile-prose :deep(h3) {
  color: var(--text-primary);
  line-height: 1.5;
  margin: 1.3em 0 0.6em;
}
.profile-prose :deep(h1) {
  font-size: 24px;
}
.profile-prose :deep(h2) {
  font-size: 20px;
}
.profile-prose :deep(h3) {
  font-size: 17px;
}
.profile-prose :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
}
.profile-prose :deep(a) {
  color: var(--el-color-primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.profile-prose :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
  padding: 16px;
  background: var(--bg-app);
  border: 1px solid var(--border-light);
  border-radius: 8px;
  line-height: 1.6;
}
.profile-prose :deep(code) {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 0.9em;
  background: var(--bg-app);
  border-radius: 3px;
  padding: 2px 4px;
}
.profile-prose :deep(pre code) {
  padding: 0;
}
.profile-prose :deep(blockquote) {
  margin: 12px 0;
  border-left: 3px solid var(--el-color-primary);
  padding: 4px 16px;
  color: var(--text-secondary);
  background: var(--bg-app);
}
.profile-prose :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
}
.profile-prose :deep(th),
.profile-prose :deep(td) {
  padding: 8px 12px;
  border: 1px solid var(--border-light);
}
</style>
