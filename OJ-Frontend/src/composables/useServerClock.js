import { onBeforeUnmount, ref } from 'vue'

/**
 * 服务端校准倒计时。
 *
 * 不依赖本地系统时间：每次同步都用服务端剩余的秒数重新锚定终点，
 * 之后只做单调递减展示。是否超时仍由服务端提交/交卷接口判定。
 */
export function useServerClock() {
  const remaining = ref(0)
  let endAt = 0
  let timer = null

  const tick = () => {
    remaining.value = Math.max(0, Math.ceil((endAt - Date.now()) / 1000))
  }
  const ensureTimer = () => {
    if (!timer) timer = setInterval(tick, 1000)
  }
  const sync = ({ remainingSeconds, deadline } = {}) => {
    if (remainingSeconds != null && Number.isFinite(Number(remainingSeconds))) {
      endAt = Date.now() + Math.max(0, Number(remainingSeconds)) * 1000
    } else if (deadline) {
      endAt = new Date(deadline).getTime()
    }
    tick()
    ensureTimer()
  }
  const stop = () => {
    if (timer) clearInterval(timer)
    timer = null
  }

  onBeforeUnmount(stop)

  return { remaining, sync, stop }
}
