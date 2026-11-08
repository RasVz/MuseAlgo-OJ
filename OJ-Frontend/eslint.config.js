import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import skipFormatting from '@vue/eslint-config-prettier/skip-formatting'

export default defineConfig([
  {
    name: 'app/files-to-lint',
    files: ['**/*.{js,mjs,jsx,vue}'],
  },

  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**', '**/archived/**', '**/playwright-report/**', '**/test-results/**']),

  {
    languageOptions: {
      globals: {
        ...globals.browser,
      },
    },
  },

  {
    // 构建配置类文件跑在 Node 环境（会用到 process、__dirname 等）。
    // 单独成块而不是把 globals.node 混进上面的 browser 配置——
    // 那样浏览器侧代码误写 process/require 也不会报错，等于丢掉了这道检查。
    name: 'app/node-env-files',
    files: ['vite.config.js', '**/*.config.js', 'scripts/**/*.{js,mjs,cjs}', 'tests/e2e/**/*.js'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },

  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  skipFormatting,

  {
    name: 'app/project-rules',
    rules: {
      // 下划线前缀 = 有意忽略（catch 占位 / 占位参数），不报未使用
      'no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
      }],
      // no-mutating-props 存量已清零（表单组件本地化改造后），恢复 error 严格守护
      'vue/no-mutating-props': 'error',
      // 单文件组件名约定：Navbar/… 为历史命名，暂放行
      'vue/multi-word-component-names': ['error', { ignores: ['Navbar'] }],
    },
  },
])
