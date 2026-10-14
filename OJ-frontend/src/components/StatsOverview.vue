<template>
  <div class="overview-grid">
    <el-card shadow="never" class="stat-card" v-for="(item, index) in items" :key="index">
      <div class="stat-content">
        <div class="stat-icon" :style="{ background: item.bg, color: item.color }">
          <el-icon><component :is="item.icon" /></el-icon>
        </div>
        <div class="stat-text">
          <div class="value">{{ item.value }}</div>
          <div class="label">{{ item.label }}</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 练习页顶部的四格指标概览（提交总数 / 通过题目 / AC 率 / 最长连胜）。
 *
 * 当前为原型占位：数值是写死的样例数据，尚未接入统计接口，
 * 因此本组件不接收任何 props，也不发请求——父页面（练习分析页）也只负责引用它。
 * 接入真实数据时的改造点：把 items 改为响应式数据源，
 * 由父组件传入或在本组件内请求统计接口，其余渲染逻辑无需改动。
 *
 * 之所以先做成纯静态列表而不是抽成可配置组件：目前只有这一处使用，
 * 过早抽象出「图标 + 数值 + 配色」的通用数据结构属于为假想需求写代码。
 *
 * 配色一律取站点 CSS 变量而非硬编码色值，这样跟随明暗主题自动切换。
 */
import { Trophy, CircleCheck, DataLine, Timer } from '@element-plus/icons-vue'

// 字段约定：value 允许是数字或带单位的字符串（如 '48.9%'、'12 天'），
// 格式化交由数据来源决定，本组件原样渲染。
const items = [
  { label: '提交总数', value: 1024, icon: DataLine, color: 'var(--el-color-primary)', bg: 'var(--el-color-primary-light-9)' },
  { label: '通过题目', value: 501, icon: CircleCheck, color: 'var(--accent-success)', bg: 'var(--accent-success-light)' },
  { label: 'AC 率', value: '48.9%', icon: Trophy, color: 'var(--accent-warning)', bg: 'var(--accent-warning-light)' },
  { label: '最长连胜', value: '12 天', icon: Timer, color: 'var(--accent-danger)', bg: 'var(--accent-danger-light)' }
]
</script>

<style scoped>
.overview-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 20px; }
.stat-card { border: none; border-radius: 12px; }
.stat-content { display: flex; align-items: center; gap: 15px; }
.stat-icon { width: 48px; height: 48px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 24px; }
.value { font-size: 24px; font-weight: bold; color: var(--text-primary); line-height: 1.2; }
.label { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
</style>