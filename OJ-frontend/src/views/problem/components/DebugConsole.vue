<template>
  <div :class="['debug-console', { 'expanded': expanded }]">
    <div class="console-tabs" @click="$emit('toggle')">
      <div class="tab-item active">
        <el-icon><Monitor /></el-icon> 
        <span style="margin: 0 5px;">自定义测试</span>
        <el-icon><ArrowDown v-if="expanded" /><ArrowUp v-else /></el-icon>
      </div>
      <div class="console-actions" @click.stop>
        <el-button type="primary" size="small" :loading="running" @click="$emit('run')">
          <el-icon><CaretRight /></el-icon> 运行
        </el-button>
      </div>
    </div>

    <div class="console-content" v-show="expanded">
      <div class="input-pane">
        <div class="pane-label">输入</div>
        <textarea :model-value="userInput" @input="$emit('update:userInput', $event.target.value)" class="custom-textarea" placeholder="输入测试数据"></textarea>
      </div>

      <div class="output-pane">
        <div class="result-box">
          <div class="pane-label">
            代码运行结果
            <span v-if="runResult.statusText" :class="['status-tag', runResult.statusType]">
              {{ runResult.statusText }}
            </span>
          </div>
          <pre class="res-display">{{ runResult.stdout || '等待运行...' }}</pre>
        </div>
        
        <div class="answer-box">
          <div class="pane-label">测试用例答案示例</div>
          <textarea :model-value="expectedOutput" @input="$emit('update:expectedOutput', $event.target.value)" class="custom-textarea mini" placeholder="期望输出"></textarea>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 调试控制台（试跑面板）：完全受控的展示组件——输入框、运行按钮与结果都归 CodeEditor，
// 这样编辑器才能在运行前决定用哪段输入，也避免出现两份不同步的输入状态。
import { Monitor, CaretRight, ArrowUp, ArrowDown } from '@element-plus/icons-vue'

defineProps({
  expanded: { type: Boolean, default: false },
  running: { type: Boolean, default: false },
  userInput: { type: String, default: '' },
  expectedOutput: { type: String, default: '' },
  runResult: { type: Object, default: () => ({ stdout: '', statusText: '', statusType: '' }) }
})
defineEmits(['toggle', 'run', 'update:userInput', 'update:expectedOutput'])
</script>

<style scoped>
.debug-console { 
  display: flex; 
  flex-direction: column; 
  background: var(--bg-surface); 
  transition: height 0.3s ease; 
}
.debug-console.expanded {
  height: 280px; 
}

.console-tabs {
  height: 35px; border-bottom: 1px solid var(--border-light); display: flex;
  justify-content: space-between; align-items: center; padding: 0 10px;
  cursor: pointer;
  user-select: none;
  background-color: var(--bg-surface);
}
.console-tabs:hover {
  background-color: var(--bg-input);
}

.tab-item.active {
  font-size: 13px; font-weight: bold; color: var(--el-color-primary);
  border-bottom: 2px solid var(--el-color-primary); height: 100%; display: flex; align-items: center;
}

.console-content { flex: 1; display: flex; padding: 8px; gap: 8px; background: var(--bg-input); overflow: hidden; }

.input-pane, .output-pane { flex: 1; display: flex; flex-direction: column; gap: 5px; }
.output-pane { gap: 8px; }

.pane-label { font-size: 12px; color: var(--text-regular); font-weight: bold; display: flex; align-items: center; justify-content: space-between; }

.custom-textarea {
  flex: 1; border: 1px solid var(--border-base); padding: 8px; font-family: 'Consolas', monospace;
  font-size: 13px; resize: none; outline: none; border-radius: 4px;
}

.mini { height: 72px; flex: none; }

.result-box { flex: 1; display: flex; flex-direction: column; gap: 5px; }
.answer-box { flex: 1; display: flex; flex-direction: column; gap: 5px; }

.res-display {
  flex: 1; margin: 0; background: var(--bg-surface); border: 1px solid var(--border-base); border-radius: 4px;
  padding: 8px; font-size: 13px; font-family: 'Consolas', monospace; overflow: auto;
}

.status-tag { padding: 2px 6px; border-radius: 3px; font-size: 12px; color: var(--text-on-accent); }
.status-tag.ac { background: var(--accent-success); }
.status-tag.wa { background: var(--accent-danger); }
</style>
