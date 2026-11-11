export function aiServiceErrorMessage(error) {
  switch (error?.errorCode) {
    case 'AI_MASTER_KEY_NOT_READY':
      return '需要部署初始化：AI 主密钥不可用。请查看页面的初始化方法；已有服务时请恢复原密钥备份。'
    case 'AI_DNS_UNAVAILABLE':
      return '环境未就绪：后端无法完成公网地址解析。请重试地址检查；持续失败时由部署者检查后端到加密 DNS 的连接。'
    case 'AI_ENDPOINT_FORBIDDEN':
      return '地址安全检查未通过：接口指向私网、回环或保留地址，请核对接口地址。'
    default:
      return error?.message || '操作失败，请稍后重试'
  }
}
