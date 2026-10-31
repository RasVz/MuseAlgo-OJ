import { computed, ref, watch } from 'vue'
import { DIFFICULTY_RANGES } from '@/utils/difficulty'

/**
 * Rating 积分趋势图的坐标计算与视图状态管理（SVG 双轴缩放/拖拽）。
 *
 * 自己用 SVG 画而不用 ECharts，是因为需要"双轴各自独立缩放 + 拖拽平移"，
 * 且背景色带要与难度色阶严格对齐；ECharts 的 grid 联动模型表达不了这个组合。
 *
 * 核心是 abs*（数据绝对边界）与 view*（当前视窗）两套坐标：
 * 缩放/拖拽只改 view*，永远被夹在 abs* 内，保证用户无法拖出数据范围。
 *
 * @param {Ref<Array>|Array} dataRef 形如 [{ rating: number }] 的积分序列
 * @param {Object} options svgWidth / svgHeight 画布尺寸
 *
 * @returns 绘图所需的 SVG 尺寸、可见色带、网格线、点集与坐标轴文案，
 *   以及 handleWheel/startDrag/onDrag/stopDrag/resetZoom 交互处理函数
 *
 * 副作用：内部 watch 数据变化时会重算 abs* 并调用 resetZoom() 重置视窗。
 */
export function useRatingChart(dataRef, { svgWidth = 800, svgHeight = 300 } = {}) {
  // 兼容 ref 与普通数组两种传参，并保证空安全（undefined/null → []）
  const data = computed(() => {
    const v = dataRef && dataRef.value !== undefined ? dataRef.value : dataRef
    return Array.isArray(v) ? v : []
  })

  // 资料页积分趋势与题库筛选共用同一套 11 档难度色阶。
  const RAW_COLOR_BANDS = [
    { min: 0, max: 800, color: '#cbd5e1', opacity: 0.55 },
    ...DIFFICULTY_RANGES.map((range, index) => ({
      min: range.min,
      max: DIFFICULTY_RANGES[index + 1]?.min ?? 9999,
      color: range.color,
      opacity: 0.42
    }))
  ]

  // === 视图状态 ===
  const absMinY = ref(0)
  const absMaxY = ref(3000)
  const viewMinY = ref(0)
  const viewMaxY = ref(3000)
  const absMinX = ref(0)
  const absMaxX = ref(10)
  const viewMinX = ref(0)
  const viewMaxX = ref(10)

  const isDragging = ref(false)
  const dragStart = ref({ x: 0, y: 0 })
  const dragSnapshot = ref({ minX: 0, maxX: 0, minY: 0, maxY: 0 })

  const getY = (rating) => {
    const range = viewMaxY.value - viewMinY.value
    if (range === 0) return svgHeight / 2
    const percent = (rating - viewMinY.value) / range
    return svgHeight - (percent * svgHeight)
  }

  const getX = (index) => {
    const range = viewMaxX.value - viewMinX.value
    if (range === 0) return svgWidth / 2
    const percent = (index - viewMinX.value) / range
    return percent * svgWidth
  }

  // === 初始化 ===
  // 数据到达 → 重算绝对边界并把视窗复位。immediate 保证调用方传入已有数据时也能生效。
  // 下界固定 800 是刻意的：820 分以下的新手在图上应该"贴着地板"，
  // 若按实际最小值自适应曲线会被首条记录放大到全屏，趋势感丢失。
  watch(() => data.value, (newData) => {
    if (newData.length > 0) {
      const ratings = newData.map(i => i.rating)
      const maxR = Math.max(...ratings)
      absMinY.value = 800
      absMaxY.value = Math.max(1000, maxR + 100)
      absMinX.value = -0.5
      absMaxX.value = newData.length - 1 + 0.5
      resetZoom()
    }
  }, { immediate: true })

  function resetZoom() {
    viewMinY.value = absMinY.value
    viewMaxY.value = absMaxY.value
    viewMinX.value = absMinX.value
    viewMaxX.value = absMaxX.value
  }

  // === 滚轮双轴缩放 ===
  // 两个轴同时缩放：趋势图里横纵轴各自缩放会让曲线斜率失去意义（看似暴涨实则只是拉了 Y 轴）。
  // 最小跨度限制（Y 50 分 / X 2 个点）用于防止缩到极端后除零或坐标轴标签糊成一片。
  const handleWheel = (e) => {
    const direction = e.deltaY > 0 ? 1 : -1
    const zoomFactor = 0.1

    // Y轴
    const rangeY = viewMaxY.value - viewMinY.value
    const changeY = rangeY * zoomFactor * direction
    let newMinY = viewMinY.value - (changeY / 2)
    let newMaxY = viewMaxY.value + (changeY / 2)
    if (newMinY < absMinY.value) newMinY = absMinY.value
    if (newMaxY > absMaxY.value) newMaxY = absMaxY.value
    if (newMaxY - newMinY > 50) {
      viewMinY.value = newMinY
      viewMaxY.value = newMaxY
    }

    // X轴
    const rangeX = viewMaxX.value - viewMinX.value
    const changeX = rangeX * zoomFactor * direction
    let newMinX = viewMinX.value - (changeX / 2)
    let newMaxX = viewMaxX.value + (changeX / 2)
    if (newMinX < absMinX.value) newMinX = absMinX.value
    if (newMaxX > absMaxX.value) newMaxX = absMaxX.value
    if (newMaxX - newMinX > 2) {
      viewMinX.value = newMinX
      viewMaxX.value = newMaxX
    }
  }

  // === 全向拖拽 ===
  // 拖拽起点存一份 view 快照：onDrag 是高频事件，若基于"上一帧的 view"增量累加，
  // 会因丢事件导致位置漂移，永远回不到起点；快照法保证拖拽绝对可逆。
  const startDrag = (e) => {
    isDragging.value = true
    dragStart.value = { x: e.clientX, y: e.clientY }
    dragSnapshot.value = {
      minX: viewMinX.value, maxX: viewMaxX.value,
      minY: viewMinY.value, maxY: viewMaxY.value
    }
  }

  const onDrag = (e) => {
    if (!isDragging.value) return

    const deltaPixelX = e.clientX - dragStart.value.x
    const deltaPixelY = e.clientY - dragStart.value.y

    // X轴
    // 像素位移转索引位移：offset 取负是因为向右拖要看到更靠后的记录（索引变小）。
    const currentRangeX = dragSnapshot.value.maxX - dragSnapshot.value.minX
    const indexPerPixel = currentRangeX / svgWidth
    const offsetIndex = deltaPixelX * indexPerPixel
    let nextMinX = dragSnapshot.value.minX - offsetIndex
    let nextMaxX = dragSnapshot.value.maxX - offsetIndex

    if (nextMinX < absMinX.value) {
      const diff = absMinX.value - nextMinX
      nextMinX += diff; nextMaxX += diff
    }
    if (nextMaxX > absMaxX.value) {
      nextMaxX = absMaxX.value; nextMinX = absMaxX.value - currentRangeX
    }
    viewMinX.value = nextMinX
    viewMaxX.value = nextMaxX

    // Y轴
    // 与 X 轴相反：向下拖要让 rating 减小（视野向低分区平移）。
    const currentRangeY = dragSnapshot.value.maxY - dragSnapshot.value.minY
    const ratingPerPixel = currentRangeY / svgHeight
    const offsetRating = deltaPixelY * ratingPerPixel
    let nextMinY = dragSnapshot.value.minY + offsetRating
    let nextMaxY = dragSnapshot.value.maxY + offsetRating

    if (nextMinY < absMinY.value) {
      nextMinY = absMinY.value; nextMaxY = absMinY.value + currentRangeY
    }
    if (nextMaxY > absMaxY.value) {
      nextMaxY = absMaxY.value; nextMinY = absMaxY.value - currentRangeY
    }
    viewMinY.value = nextMinY
    viewMaxY.value = nextMaxY
  }

  const stopDrag = () => {
    isDragging.value = false
  }

  // === 绘图计算 ===
  // 色带上下各外扩 1000：完全不外扩的话，缩放到某个难度区间时
  // 相邻色带会瞬间从视野消失，看不出当前处于哪一档。
  const visibleBands = computed(() => {
    return RAW_COLOR_BANDS.map(band => {
      const safeMax = Math.min(band.max, viewMaxY.value + 1000)
      const safeMin = Math.max(band.min, viewMinY.value - 1000)
      const yTop = getY(safeMax)
      const yBottom = getY(safeMin)
      return { ...band, y: yTop, height: Math.max(0, yBottom - yTop), bottomY: yBottom }
    })
  })

  // 网格线每 5 条画一次，最后一个点强制画：保证右端有边界参考线。
  const gridLinesX = computed(() => {
    const lines = []
    for (let i = 0; i < data.value.length; i++) {
      if (i % 5 === 0 || i === data.value.length - 1) {
        const x = getX(i)
        if (x >= -10 && x <= svgWidth + 10) lines.push({ x })
      }
    }
    return lines
  })

  const allPoints = computed(() => {
    return data.value.map((item, index) => ({
      x: getX(index), y: getY(item.rating), index, originData: item
    }))
  })

  // 超出画布 ±50px 的点裁掉：polyline 会把视野外的点一起连线，
  // 拖拽时线段会从画面边缘斜插进来，看着像折线断裂。
  const visiblePoints = computed(() => {
    return allPoints.value.filter(p => p.x >= -50 && p.x <= svgWidth + 50)
  })

  const chartPoints = computed(() => {
    return allPoints.value.map(p => `${p.x},${p.y}`).join(' ')
  })

  const maxRatingVal = computed(() => {
    if (!data.value.length) return '—'
    return Math.max(...data.value.map(i => i.rating))
  })

  const currentRatingVal = computed(() => {
    if (!data.value.length) return '—'
    return data.value[data.value.length - 1].rating
  })

  return {
    SVG_WIDTH: svgWidth,
    SVG_HEIGHT: svgHeight,
    isDragging,
    visibleBands,
    gridLinesX,
    allPoints,
    visiblePoints,
    chartPoints,
    maxRatingVal,
    currentRatingVal,
    handleWheel,
    startDrag,
    onDrag,
    stopDrag,
    resetZoom
  }
}
