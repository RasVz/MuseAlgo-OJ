import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ArrowDown, ArrowLeft, ArrowUp, Back, Bell, Check, CircleCheck, Close,
  CopyDocument, DataLine, Delete, Document, Edit, EditPen, Key, List,
  Lock, MagicStick, Message, Notebook, Odometer, Plus, Reading, Refresh,
  Remove, School, Search, Setting, Star, StarFilled, SwitchButton, TopRight,
  Upload, User, View
} from '@element-plus/icons-vue'
// 函数式 API（ElMessage/ElMessageBox/ElNotification/ElLoading）样式：
// 组件样式由 unplugin 按需注入，此处仅补显式 import 的 API 样式
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/notification/style/css'
import 'element-plus/es/components/loading/style/css'
import './styles/tokens.css'
import App from './App.vue'
import router from './router'
import dicebearPlugin from './plugins/dicebear';
import { trackError } from './utils/metrics'

// 体验埋点：全局 JS 错误 / 未处理 Promise 拒绝（匿名聚合上报）
window.addEventListener('error', (e) => trackError(e.error || e.message))
window.addEventListener('unhandledrejection', (e) => trackError(e.reason))

const app = createApp(App)

// 全局注册 Element Plus 图标（按需白名单）：支持 el-* 组件 icon="Xxx" 字符串属性按名解析。
// 2026-08-16 由全量注册改为按需：项目内实际用到的 35 个（含动态字符串 Star/StarFilled）；
// 新增图标使用前请先在此登记（组件内直接 import 的不受影响）。
const ICON_WHITELIST = [
  ArrowDown, ArrowLeft, ArrowUp, Back, Bell, Check, CircleCheck, Close,
  CopyDocument, DataLine, Delete, Document, Edit, EditPen, Key, List,
  Lock, MagicStick, Message, Notebook, Odometer, Plus, Reading, Refresh,
  Remove, School, Search, Setting, Star, StarFilled, SwitchButton, TopRight,
  Upload, User, View
]
for (const component of ICON_WHITELIST) {
  app.component(component.name, component)
}

app.use(createPinia())
app.use(router)
app.use(dicebearPlugin);

app.mount('#app')
