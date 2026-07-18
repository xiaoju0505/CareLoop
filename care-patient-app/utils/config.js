/**
 * 后端地址：
 * - App / 小程序：直连公网
 * - H5 调试：走空前缀 + manifest 里的 devServer.proxy，避免浏览器 CORS / OPTIONS 403
 */
// #ifdef H5
export const API_BASE = ''
// #endif
// #ifndef H5
export const API_BASE = 'https://fromfreedom.top'
// #endif
