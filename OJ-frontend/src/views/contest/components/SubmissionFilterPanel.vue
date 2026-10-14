<template>
  <el-card shadow="never" class="filter-card">
    <template #header>
      <div class="side-title">筛选器</div>
    </template>

    <el-form label-position="top" class="filter-form">
      <el-form-item label="题目">
        <el-select v-model="localFilters.problemId" clearable placeholder="全部题目">
          <el-option
            v-for="p in problemOptions"
            :key="p.value"
            :label="p.label"
            :value="p.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="语言">
        <el-select v-model="localFilters.language" clearable placeholder="全部语言">
          <el-option
            v-for="lang in languageOptions"
            :key="lang"
            :label="lang"
            :value="lang"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="状态">
          <el-select v-model="localFilters.status" clearable placeholder="全部状态">
            <el-option label="AC" :value="1" />
            <el-option label="WA" :value="2" />
            <el-option label="CE" :value="3" />
            <el-option label="RE" :value="4" />
            <el-option label="TLE" :value="5" />
          </el-select>
        </el-form-item>

      <el-button type="primary" class="w-full" @click="handleApply">应用筛选</el-button>
    </el-form>
  </el-card>
</template>

<script setup>
// 比赛提交筛选面板：题目 / 语言 / 状态三项。
// 编辑期间只改本地副本，点「应用」才提交——避免每改一项就打一次接口。
import { ref, watch } from 'vue'

const props = defineProps({
  filters: { type: Object, required: true },
  problemOptions: { type: Array, default: () => [] },
  languageOptions: { type: Array, default: () => [] }
})
const emit = defineEmits(['apply', 'update:filters'])

// 本地筛选状态：不在编辑过程中直接改 props（父组件重置时经 watch 同步回本地）
const localFilters = ref({ ...props.filters })
// 监听父组件：用户可能在外部点「重置」，这里要跟着清空，
// 否则重置后面板还显示旧条件，看起来像没生效。
watch(() => props.filters, (v) => {
  localFilters.value = { ...v }
})

function handleApply() {
  emit('update:filters', { ...localFilters.value })
  emit('apply')
}
</script>

<style scoped>
.side-title {
  font-weight: 600;
  color: #303133;
}
.filter-form :deep(.el-select) {
  width: 100%;
}
.w-full {
  width: 100%;
}
</style>
