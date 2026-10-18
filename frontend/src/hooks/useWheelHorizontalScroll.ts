import { useEffect, type RefObject } from 'react'

/**
 * 悬停横向滚动容器时，把纵向滚轮转换为横向滚动。
 * 仅在容器仍可继续滚动（当前方向还有空间）时拦截，边缘处不阻断页面滚动。
 * 使用原生非 passive 监听，避免 React 合成事件无法 preventDefault 的问题。
 */
export function useWheelHorizontalScroll<T extends HTMLElement>(ref: RefObject<T | null>) {
  useEffect(() => {
    const element = ref.current
    if (!element) {
      return
    }
    const onWheel = (event: WheelEvent) => {
      if (element.scrollWidth <= element.clientWidth) {
        return
      }
      if (event.deltaY === 0 && event.deltaX === 0) {
        return
      }
      const delta = Math.abs(event.deltaY) > Math.abs(event.deltaX) ? event.deltaY : event.deltaX
      const max = element.scrollWidth - element.clientWidth
      if ((delta > 0 && element.scrollLeft >= max - 1) || (delta < 0 && element.scrollLeft <= 0)) {
        return
      }
      event.preventDefault()
      element.scrollLeft += delta
    }
    element.addEventListener('wheel', onWheel, { passive: false })
    return () => element.removeEventListener('wheel', onWheel)
  }, [ref])
}
