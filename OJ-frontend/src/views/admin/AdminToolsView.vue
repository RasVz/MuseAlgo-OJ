<template>
  <div class="admin-tools admin-scroll-page">
    <div class="page-header">
      <div class="page-title">站内工具</div>
      <div class="page-desc">管理中心的小工具集合，点击直达</div>
    </div>

    <div v-for="group in groups" :key="group.title" class="group">
      <div class="group-title">{{ group.title }}</div>
      <div class="card-grid">
        <div
          v-for="item in group.items"
          :key="item.path"
          class="tool-card"
          @click="router.push(item.path)"
        >
          <el-icon :size="24" class="card-icon"><component :is="item.icon" /></el-icon>
          <div class="card-label">{{ item.label }}</div>
          <div class="card-desc">{{ item.desc }}</div>
        </div>
      </div>
    </div>

    <div class="more-hint">更多小工具陆续上架中…</div>
  </div>
</template>

<script setup>
// 管理后台工具聚合页：只负责分组导航，不含任何业务逻辑。
// 新增工具只需在 groups 里加一项，真正的功能页各自独立。
import { useRouter } from 'vue-router'
import { EditPen, DocumentChecked } from '@element-plus/icons-vue'

const router = useRouter()

const groups = [
  {
    title: 'AI 命题',
    items: [
      { label: '题面生成', desc: '输入命题要求，AI 生成完整题面并保存为题目', path: '/admin/tools/draft', icon: EditPen }
    ]
  },
  {
    title: '检查工具',
    items: [
      { label: '查重报告', desc: '班级 / 比赛内同题提交两两比对，识别疑似抄袭', path: '/admin/plagiarism', icon: DocumentChecked }
    ]
  }
]
</script>

<style scoped>
.admin-tools { padding: 20px; }
.page-header { margin-bottom: 18px; }
.page-title { font-size: 20px; font-weight: 700; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }

.group { margin-bottom: 22px; }
.group-title { font-size: 13px; font-weight: 600; color: var(--text-regular); margin-bottom: 10px; }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; }
.tool-card {
  background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: 12px;
  padding: 18px 16px; cursor: pointer; transition: all .15s;
}
.tool-card:hover { border-color: #6366f1; box-shadow: 0 6px 16px rgba(99, 102, 241, .12); transform: translateY(-2px); }
.card-icon { color: #6366f1; }
.card-label { font-size: 15px; font-weight: 600; margin-top: 10px; }
.card-desc { color: var(--text-secondary); font-size: 12px; margin-top: 4px; line-height: 1.5; }
.more-hint { margin-top: 14px; font-size: 12px; color: var(--text-placeholder); text-align: center; }
</style>
