import { useEffect, useRef, useState, type RefObject } from 'react'

/**
 * 吸顶卡片滚动自动隐藏/浮回（图库工具栏与智能搜索卡片共用同一逻辑）：
 * - 向下滚动超过阈值立即隐藏（上移淡出）；
 * - 向上滚动需"连续"累计超过 12px 才浮回：任何向下滚动都会清零上滑进度，
 *   因此触控板/惯性滚轮在底部的微小回弹（正负抖动互相抵消）不会误触发；
 * - 回到接近顶部（24px 以内）强制显示。
 */
export function useScrollHidden(ref: RefObject<HTMLElement | null>): boolean {
  const [hidden, setHidden] = useState(false)
  const hiddenRef = useRef(false)
  useEffect(() => {
    const scroller = ref.current?.closest('main')
    if (!scroller) {
      return
    }
    const element = scroller as HTMLElement
    let last = element.scrollTop
    let accumulated = 0
    const handle = () => {
      const current = element.scrollTop
      const delta = current - last
      last = current
      if (current <= 24) {
        accumulated = 0
        if (hiddenRef.current) {
          hiddenRef.current = false
          setHidden(false)
        }
        return
      }
      if (hiddenRef.current) {
        if (delta < 0) {
          accumulated += delta
          if (accumulated <= -12) {
            hiddenRef.current = false
            accumulated = 0
            setHidden(false)
          }
        } else if (delta > 0) {
          accumulated = 0
        }
      } else if (delta > 6) {
        hiddenRef.current = true
        setHidden(true)
      }
    }
    element.addEventListener('scroll', handle, { passive: true })
    return () => {
      element.removeEventListener('scroll', handle)
    }
  }, [ref])
  return hidden
}
