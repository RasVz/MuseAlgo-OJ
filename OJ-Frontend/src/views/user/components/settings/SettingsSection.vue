<template>
  <section class="settings-section" :class="{ 'settings-section--danger': tone === 'danger' }">
    <header class="settings-section-header">
      <div class="settings-section-heading">
        <h3 class="settings-section-title">{{ title }}</h3>
        <p v-if="description" class="settings-section-description">{{ description }}</p>
      </div>
      <div class="settings-section-meta">
        <span v-if="dirty" class="settings-dirty-badge" role="status">未保存</span>
        <slot name="actions" />
      </div>
    </header>
    <div class="settings-section-body">
      <slot />
    </div>
  </section>
</template>

<script setup>
// 分区容器：只提供标题、说明、脏状态与操作位，不套第二层卡片。
defineProps({
  title: { type: String, required: true },
  description: { type: String, default: '' },
  dirty: { type: Boolean, default: false },
  tone: { type: String, default: 'default' }
})
</script>

<style scoped>
.settings-section {
  padding: 18px 0 8px;
  border-bottom: 1px solid var(--border-light);
}
.settings-section:last-child {
  border-bottom: none;
}
.settings-section--danger {
  margin: 12px 0 16px;
  padding: 16px 20px 8px;
  border: 1px solid color-mix(in srgb, var(--accent-danger) 45%, transparent);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--accent-danger) 10%, transparent);
}
.settings-section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.settings-section-heading {
  min-width: 0;
}
.settings-section-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.settings-section-description {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-regular);
}
.settings-section-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.settings-dirty-badge {
  font-size: 12px;
  color: var(--text-primary);
  background: color-mix(in srgb, var(--accent-warning) 16%, transparent);
  border-radius: 999px;
  padding: 2px 10px;
  white-space: nowrap;
}
.settings-section-body {
  margin-top: 8px;
}
@media (max-width: 680px) {
  .settings-section-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .settings-section-meta {
    width: 100%;
    flex-wrap: wrap;
  }
  .settings-section--danger {
    padding: 14px 14px 6px;
  }
}
</style>
