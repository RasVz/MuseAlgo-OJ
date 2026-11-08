// src/plugins/dicebear.js
import { generateAvatar } from '@/utils/dicebear';

export default {
  install(app) {
    // 方式：使用 Provide（组件内用 inject 访问）
    app.provide('dicebear', { generateAvatar });
  },
};