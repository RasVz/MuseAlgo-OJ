<script setup>
/**
 * NavbarContext — 将 slot 内容注入到顶部 Navbar 的上下文区域
 * 通过 provide/inject 与 Navbar.vue 通信，替代原先的 Teleport + DOM id 模式
 */
import { useSlots, inject, onMounted, onUnmounted, h } from 'vue'

const slots = useSlots()
const setContext = inject('setNavbarContext')

// 包装 slot 内容为一个可渲染的组件对象
const wrapper = {
  render() {
    return h('div', {
      class: 'navbar-dynamic-content',
      style: 'width:100%;height:100%;display:flex;align-items:center'
    }, slots.default?.())
  }
}

// provider 缺失时（如管理中心 AdminLayout 下）优雅跳过，不阻断页面挂载
onMounted(() => setContext?.(wrapper))
onUnmounted(() => setContext?.(null))
</script>

<template>
  <div style="display: none" />
</template>
