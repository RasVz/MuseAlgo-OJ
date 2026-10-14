// src/utils/dicebear.js
// 使用 @dicebear/core + @dicebear/avataaars 本地生成头像，避免依赖外部 API
import { createAvatar } from '@dicebear/core';
import * as avataaars from '@dicebear/avataaars';

/**
 * 根据种子字符串生成 DiceBear avataaars 风格的头像 Data URI。
 * @param {string} seed - 种子（如用户名），相同种子生成相同头像
 * @returns {string} SVG Data URI 可直接用作 img src
 */
export function generateAvatar(seed) {
  const safeSeed = seed || 'Felix';
  const avatar = createAvatar(avataaars, {
    seed: safeSeed,
    size: 128,
    backgroundColor: ['b6e3f4', 'c0aede', 'd1d4f9', 'ffd5dc', 'ffdfbf'],
  });
  return avatar.toDataUri();
}

/**
 * 默认头像（无种子时使用）。
 * 直接生成一次并缓存，避免每次重复计算。
 */
let _defaultAvatar = null;
export function getDefaultAvatar() {
  if (!_defaultAvatar) {
    _defaultAvatar = generateAvatar('Felix');
  }
  return _defaultAvatar;
}
