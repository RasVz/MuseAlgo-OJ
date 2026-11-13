<template>
  <!-- 折叠态：只留 44px 竖排把手，表单整体 v-if 卸载，避免键盘 Tab 进入不可见控件 -->
  <aside v-if="collapsed" class="brief-rail">
    <button
      type="button"
      class="rail-toggle"
      :aria-expanded="false"
      :aria-controls="PANEL_ID"
      title="展开命题简报"
      @click="$emit('update:collapsed', false)"
    >
      <el-icon><Expand /></el-icon>
      <span class="rail-text">命题简报</span>
    </button>
  </aside>

  <aside v-else :id="PANEL_ID" class="brief-panel" aria-label="命题简报">
    <header class="panel-head">
      <div class="panel-head-main">
        <span class="eyebrow-badge">命题工坊</span>
        <h2 class="panel-title">命题简报</h2>
      </div>
      <el-button
        text
        size="small"
        :icon="Fold"
        :aria-expanded="true"
        :aria-controls="PANEL_ID"
        @click="$emit('update:collapsed', true)"
      >
        收起
      </el-button>
    </header>

    <el-form class="brief-form" label-position="top" :disabled="disabled" @submit.prevent>
      <div v-if="QUICK_STARTS.length" class="quick-starts">
        <span class="quick-label">快速开始</span>
        <button
          v-for="preset in QUICK_STARTS"
          :key="preset.id"
          type="button"
          class="quick-chip"
          :disabled="disabled || busy"
          :title="preset.description"
          @click="$emit('quick-start', preset.brief)"
        >
          {{ preset.label }}
        </button>
      </div>

      <el-collapse v-model="openGroups">
        <!-- 1. 教学目标 -->
        <el-collapse-item title="教学目标" name="goal">
          <el-form-item label="知识点" for="brief-knowledge">
            <el-select
              id="brief-knowledge"
              :model-value="brief.knowledgePoint"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入知识点"
              class="w-full"
              @update:model-value="set('knowledgePoint', $event)"
            >
              <el-option v-for="k in KNOWLEDGE_POINT_OPTIONS" :key="k" :label="k" :value="k" />
            </el-select>
          </el-form-item>

          <el-form-item label="适用年级" for="brief-grade">
            <el-select
              id="brief-grade"
              :model-value="brief.grade"
              filterable
              placeholder="如：初一"
              class="w-full"
              @update:model-value="set('grade', $event)"
            >
              <el-option v-for="g in GRADE_OPTIONS" :key="g" :label="g" :value="g" />
            </el-select>
          </el-form-item>

          <el-form-item label="难度">
            <div class="difficulty-row">
              <el-select
                :model-value="bandLabel"
                class="difficulty-band"
                @update:model-value="pickBand"
              >
                <el-option v-for="b in BANDS" :key="b.key" :label="b.label" :value="b.label" />
              </el-select>
              <el-input-number
                :model-value="brief.difficultyScore"
                :min="800"
                :max="3000"
                :step="100"
                controls-position="right"
                class="difficulty-score"
                @update:model-value="setScore"
                @blur="snapScore"
              />
            </div>
            <div class="field-help">分值决定档位名，输入后按 100 取整</div>
          </el-form-item>
        </el-collapse-item>

        <!-- 2. 题目设定 -->
        <el-collapse-item title="题目设定" name="setting">
          <el-form-item label="场景类型" for="brief-scenario">
            <el-select
              id="brief-scenario"
              :model-value="brief.scenarioType"
              class="w-full"
              @update:model-value="set('scenarioType', $event)"
            >
              <el-option v-for="s in SCENARIO_TYPE_OPTIONS" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>

          <el-form-item label="世界观命名" for="brief-theme">
            <el-select
              id="brief-theme"
              :model-value="brief.theme"
              class="w-full"
              @update:model-value="set('theme', $event)"
            >
              <el-option
                v-for="option in THEME_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="brief.theme === 'custom'" label="自定义设定" for="brief-custom-theme">
            <el-input
              id="brief-custom-theme"
              :model-value="brief.customTheme"
              maxlength="40"
              show-word-limit
              placeholder="如：星际邮局的分拣机器人"
              @update:model-value="set('customTheme', $event)"
            />
          </el-form-item>

          <el-form-item label="数据规模" for="brief-scale">
            <el-input
              id="brief-scale"
              :model-value="brief.scale"
              placeholder="如 n ≤ 10^5"
              @update:model-value="set('scale', $event)"
            />
          </el-form-item>

          <el-form-item label="样例组数" for="brief-sample-count">
            <el-select
              id="brief-sample-count"
              :model-value="brief.sampleCount"
              class="w-full"
              @update:model-value="set('sampleCount', Number($event))"
            >
              <el-option v-for="n in SAMPLE_COUNT_OPTIONS" :key="n" :label="`${n} 组`" :value="n" />
            </el-select>
          </el-form-item>
        </el-collapse-item>

        <!-- 3. 风格与约束 -->
        <el-collapse-item title="风格与约束" name="style">
          <el-form-item label="禁用词 / 禁忌" for="brief-forbidden">
            <el-input
              id="brief-forbidden"
              :model-value="brief.forbidden"
              placeholder="逗号分隔，如：位运算, 网络流"
              @update:model-value="set('forbidden', $event)"
            />
          </el-form-item>

          <el-form-item label="参考题目" for="brief-reference">
            <el-input
              id="brief-reference"
              :model-value="brief.reference"
              placeholder="如：NOIP2018 铺设道路"
              @update:model-value="set('reference', $event)"
            />
          </el-form-item>
        </el-collapse-item>

        <!-- 4. 进阶 -->
        <el-collapse-item title="进阶要求" name="advanced">
          <el-form-item label="附加说明" for="brief-extra">
            <el-input
              id="brief-extra"
              :model-value="brief.extra"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="如：输出需带提示语；样例要覆盖边界"
              @update:model-value="set('extra', $event)"
            />
          </el-form-item>

          <el-form-item label="最终提示词">
            <div class="prompt-block">
              <el-button
                size="small"
                :loading="promptLoading"
                :disabled="disabled"
                @click="$emit('preview-prompt')"
              >
                {{ promptPreview ? '刷新预览' : '查看最终提示词' }}
              </el-button>
              <span class="field-help">只读，用于核对模型实际收到的内容</span>
            </div>
            <div v-if="promptPreview" class="prompt-preview">
              <div class="prompt-label">system</div>
              <pre class="prompt-pre">{{ promptPreview.system || '（未提供）' }}</pre>
              <div class="prompt-label">user</div>
              <pre class="prompt-pre">{{ promptPreview.user || '（未提供）' }}</pre>
            </div>
          </el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>

    <footer class="panel-foot">
      <div class="foot-row">
        <div class="variant-picker" role="group" aria-label="生成方案数">
          <span class="variant-label">方案</span>
          <el-radio-group
            :model-value="variants"
            size="small"
            :disabled="disabled || busy"
            @update:model-value="$emit('update:variants', Number($event))"
          >
            <el-radio-button v-for="n in [1, 2, 3]" :key="n" :value="n">{{ n }}</el-radio-button>
          </el-radio-group>
        </div>
        <el-button
          type="primary"
          class="gen-btn"
          :loading="busy"
          :disabled="disabled || busy || !canGenerate"
          @click="$emit('generate')"
        >
          生成题面{{ variants > 1 ? `（${variants} 个方案）` : '' }}
        </el-button>
      </div>
      <p v-if="!canGenerate" class="foot-error" role="status">{{ firstError }}</p>
    </footer>
  </aside>
</template>

<script setup>
// 左栏命题简报：纯表单，不发起请求。
// 折叠态由父级控制（collapsed），折叠时整棵表单 v-if 卸载，避免键盘 Tab 进入不可见字段。
//
// 选项列表目前是占位常量：draftModel.js 落地后统一改为从该模块导入，
// 组件内不再保留第二份枚举。
import { computed, ref } from 'vue'
import { Expand, Fold } from '@element-plus/icons-vue'
import { DIFFICULTY_RANGES, getDifficultyRange } from '@/utils/difficulty'
import {
  GRADE_OPTIONS,
  KNOWLEDGE_POINT_OPTIONS,
  QUICK_STARTS,
  SAMPLE_COUNT_OPTIONS,
  SCENARIO_TYPE_OPTIONS,
  THEME_OPTIONS,
  validateBrief
} from './draftModel'

const PANEL_ID = 'draft-brief-panel'

const props = defineProps({
  brief: { type: Object, required: true },
  variants: { type: Number, default: 1 },
  disabled: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
  collapsed: { type: Boolean, default: false },
  promptPreview: { type: Object, default: null },
  promptLoading: { type: Boolean, default: false }
})

const emit = defineEmits([
  'update:brief',
  'update:variants',
  'update:collapsed',
  'generate',
  'preview-prompt'
])

// 选项与校验全部来自 draftModel，组件内不再保留第二份枚举/规则
const BANDS = DIFFICULTY_RANGES

const openGroups = ref(['goal', 'setting'])
const bandLabel = computed(() => getDifficultyRange(props.brief.difficultyScore)?.label || '')
const briefErrors = computed(() => validateBrief(props.brief))
const canGenerate = computed(() => briefErrors.value.length === 0)
const firstError = computed(() => briefErrors.value[0] || '')

// 所有写入统一走 set：父级 brief 是唯一数据源，本组件不持有副本
const set = (key, value) => emit('update:brief', { ...props.brief, [key]: value })

// 选档位 → 用该档起始分作为默认值，分值仍可再手调
const pickBand = (label) => {
  const band = BANDS.find((b) => b.label === label)
  if (band) set('difficultyScore', band.min)
}

const setScore = (value) => set('difficultyScore', Number(value) || 800)

// el-input-number 允许手输任意整数；1350 这类值在 difficulty.js 里落不到任何档位，
// 会显示「未评定」。失焦时吸附到最近的 100 并夹在 800–3000，保证档位永远可反查。
const snapScore = () => {
  const raw = Number(props.brief.difficultyScore) || 800
  const snapped = Math.min(3000, Math.max(800, Math.round(raw / 100) * 100))
  if (snapped !== props.brief.difficultyScore) set('difficultyScore', snapped)
}
</script>

<style scoped>
.brief-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.brief-form {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 4px 14px 8px;
  overscroll-behavior: contain;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px 10px;
  border-bottom: 1px solid var(--border-light);
}
.panel-head-main { min-width: 0; }
.eyebrow-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: var(--accent-primary);
  background: var(--bg-surface-hover);
  border: 1px solid var(--border-light);
  border-radius: 999px;
  padding: 1px 8px;
}
.panel-title {
  margin: 6px 0 0;
  font-size: 15px;
  line-height: 1.3;
  color: var(--text-primary);
}
.panel-foot {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 14px;
  border-top: 1px solid var(--border-light);
  background: var(--bg-surface);
}
.foot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.foot-error {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--accent-danger);
}

.quick-starts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 10px 0 4px;
}
.quick-label { font-size: 12px; color: var(--text-secondary); }
.quick-chip {
  padding: 2px 10px;
  border: 1px solid var(--border-base);
  border-radius: 999px;
  background: var(--bg-surface);
  color: var(--text-regular);
  font-size: 12px;
  cursor: pointer;
}
.quick-chip:hover:not(:disabled) {
  border-color: var(--accent-primary);
  color: var(--accent-primary);
}
.quick-chip:disabled { cursor: not-allowed; opacity: 0.6; }
.quick-chip:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 1px; }
.variant-picker { display: flex; align-items: center; gap: 8px; min-width: 0; }
.variant-label { font-size: 12px; color: var(--text-secondary); }
.gen-btn { flex-shrink: 0; }

.difficulty-row { display: flex; gap: 8px; width: 100%; }
.difficulty-band { flex: 1; min-width: 0; }
.difficulty-score { width: 110px; flex-shrink: 0; }

.field-help {
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
  margin-top: 4px;
}
.w-full { width: 100%; }

.prompt-block { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.prompt-preview {
  margin-top: 8px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  background: var(--bg-app);
  padding: 8px 10px;
}
.prompt-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.prompt-pre {
  margin: 0 0 8px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-regular);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 160px;
  overflow-y: auto;
}
.prompt-pre:last-child { margin-bottom: 0; }

/* 折叠把手：44px 竖向，点击区 ≥32×32 */
.brief-rail {
  display: flex;
  justify-content: center;
  height: 100%;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
}
.rail-toggle {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 96px;
  padding: 12px 0;
  border: 0;
  background: transparent;
  color: var(--text-regular);
  font-size: 12px;
  cursor: pointer;
}
.rail-toggle:hover { background: var(--bg-surface-hover); color: var(--text-primary); }
.rail-toggle:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: -2px; }
.rail-text { writing-mode: vertical-rl; letter-spacing: 2px; }

:deep(.el-collapse) { border-top: 0; border-bottom: 0; }
:deep(.el-collapse-item__header) {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  height: 42px;
  line-height: 42px;
  border-bottom-color: var(--border-light);
}
:deep(.el-collapse-item__wrap) { border-bottom-color: var(--border-light); }

@media (prefers-reduced-motion: reduce) {
  .rail-toggle { transition: none; }
}
</style>
