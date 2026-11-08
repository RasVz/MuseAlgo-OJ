<template>
  <div class="submission-table">
    <el-table 
      :data="data" 
      style="width: 100%" 
      v-loading="loading" 
      stripe
      height="100%"
    >
      <el-table-column label="#" prop="id" width="100" align="center" />
      
      <el-table-column label="提交时间" min-width="170">
        <template #default="{ row }">
          <span class="mono-font time-text">{{ formatTime(row.createTime) }}</span>
        </template>
      </el-table-column>
      
      <el-table-column label="用户" min-width="140">
        <template #default="{ row }">
          <span class="link-text" @click.stop="$emit('view-user', row.userId)">
            {{ row.username || row.userId }}
          </span>
        </template>
      </el-table-column>
      
      <el-table-column label="题目" min-width="220">
        <template #default="{ row }">
          <div class="link-text" @click.stop="$emit('view-problem', row.problemId)">
            <span style="font-weight: bold; margin-right: 5px;">P{{ row.problemId }}</span>
            <span v-if="row.problemTitle">
              {{ row.problemTitle }}
            </span>
          </div>
        </template>
      </el-table-column>
      
      <el-table-column label="结果" width="180" align="center">
        <template #default="{ row }">
          <el-tag 
            :color="getStatusColor(row.status)" 
            class="status-tag"
            @click.stop="$emit('view-detail', row.id)"
            effect="dark"
          >
            {{ getStatusText(row.status, row.judgePhase) }}
          </el-tag>
        </template>
      </el-table-column>

      <!-- <el-table-column label="耗时" width="100" align="center">
        <template #default="{ row }">
          {{ row.runTime }}ms
        </template>
      </el-table-column>
      
      <el-table-column label="内存" width="100" align="center">
        <template #default="{ row }">
          {{ (row.runMemory / 1024).toFixed(2) }}MB
        </template>
      </el-table-column> -->
      
      <el-table-column label="语言" prop="language" width="100" align="center">
        <template #default="{ row }">
          <el-tag 
            size="small" 
            effect="plain"
            class="lang-tag"
          >
            {{ row.language }}
          </el-tag>
        </template>
      </el-table-column>

    </el-table>
  </div>
</template>

<script setup>
// 提交记录表格：受控展示组件，数据与翻页由 SubmissionView 持有。
// 判题中的提交（status=0）额外展示 judgePhase 细分阶段，
// 否则一整页都是笼统的 "Pending"，用户不知道程序卡在哪一步。
defineProps({
  data: {
    type: Array,
    default: () => []
  },
  loading: {
    type: Boolean,
    default: false
  }
})

// 定义对外抛出的事件
defineEmits(['view-user', 'view-problem', 'view-detail'])

// 工具函数
const getStatusText = (s, phase) => {
  if (Number(s) === 0) return ({ QUEUED: '排队中', COMPILING: '编译中', JUDGING: '判题中', FAILED: '判题服务异常' }[phase] || '排队中')
  return ({
  0: 'Pending', 
  1: 'Accepted', 
  2: 'Wrong Answer', 
  3: 'Compile Error', 
  4: 'Runtime Error', 
  5: 'Time Limit Exceeded' 
}[s] || 'Unknown')
}

// 颜色按"结果严重程度"分：CE 与 RE 都是黄/橙（代码本身有问题），
// TLE 用信息色（算法不对但不致命），让用户一眼分辨该先看哪一类问题。
const getStatusColor = (s) => ({
  1: 'var(--accent-success)', // 自定义绿色 AC
  2: 'var(--accent-danger)', // 自定义红色 WA
  3: 'var(--accent-warning)', // 自定义黄色 CE
  4: 'var(--accent-warning)', // 自定义橙色 RE
  5: 'var(--accent-info)', // 自定义紫色 TLE
}[s] || 'var(--text-secondary)') // 默认灰色 Pending

const formatTime = (t) => t ? t.replace('T', ' ').substring(0, 19) : '-'
</script>

<style scoped>
.submission-table {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* 时间列：等宽字体，不换行 */
.mono-font {
  font-family: Consolas, Monaco, monospace;
  color: var(--text-regular);
}
.time-text {
  white-space: nowrap; /* 核心：强制一行显示 */
  display: inline-block;
}

/* 链接样式：蓝色，可点击，Hover下划线 */
.link-text {
  cursor: pointer;
  color: var(--el-color-primary); /* Element Blue */
  font-weight: 500;
  transition: all 0.2s;
  display: inline-block;
}
.link-text:hover {
  color: var(--el-color-primary-dark-2); /* 深一点的蓝色 */
  text-decoration: underline;
}

/* 状态标签 Hover 放大效果 */
.status-tag {
  cursor: pointer;
  min-width: 100px;
  font-weight: bold;
  transition: transform 0.1s;
  border: none;
}
.status-tag:hover {
  transform: scale(1.05);
  opacity: 0.9;
}

/* 语言标签：加粗，显眼一点 */
.lang-tag {
  font-weight: bold;
  color: var(--el-color-primary); /* 稍微显眼的主题色，或者直接用 var(--el-color-primary) */
  border-color: var(--el-color-primary);
  background-color: var(--el-fill-color);
}
</style>
