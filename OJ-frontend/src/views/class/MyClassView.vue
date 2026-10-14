<template>
  <div class="dashboard-content">
    <NavbarContext>
      <div style="font-size: 18px; font-weight: bold; color: #303133;">
        睿思学院
      </div>
    </NavbarContext>

    <el-row :gutter="20" class="full-height-row">
      <el-col :span="17" class="left-col">
        <MyClassList />
      </el-col>
      <el-col :span="7" class="right-col">
        <div class="sidebar-item">
          <PendingHomework />
        </div>
        <div class="sidebar-item flex-fill">
          <SmartRecommendations />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
/**
 * 我的班级页：左侧班级列表，右侧两个侧栏卡片（待完成作业计数 + 题目推荐）。
 *
 * 本组件只负责布局与子组件编排，不持有任何业务状态、不发请求：
 * 三个子组件各自独立取数（MyClassList 取班级列表、PendingHomework 取待办计数、
 * SmartRecommendations 取推荐题目），互不依赖。
 *
 * 这么拆分的理由是三个数据源的刷新时机不同：
 * 待办计数要在交完作业后尽快更新、推荐列表只在进入时取一次、班级列表还要支持切换班级，
 * 收在一个组件里会导致任何一个变化都要重拉全部。
 *
 * 右栏用 flex + overflow-y: auto 而非固定高度，
 * 是因为待办卡与推荐卡的内容高度随数据变化（0 条待办时卡片会变矮），
 * 写死高度会出现内容被裁切或下方留大片空白。
 */
import NavbarContext from '@/components/NavbarContext.vue'
import MyClassList from '@/components/MyClassList.vue'
import PendingHomework from '@/components/PendingHomework.vue'
import SmartRecommendations from '@/components/SmartRecommendations.vue'
</script>

<style scoped>
.dashboard-content {
  height: calc(100vh - 85px - 40px);
  overflow: hidden;
}
.full-height-row {
  height: 100%;
}
.left-col, .right-col {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.right-col { 
  margin-bottom: 0; 
  overflow-y: auto;
  padding-right: 4px;
}

.sidebar-top { min-height: 250px; }

.sidebar-item {
  margin-bottom: 20px;
}

.flex-fill {
  flex: 1;
  min-height: 0;
  margin-bottom: 0;
}
</style>
