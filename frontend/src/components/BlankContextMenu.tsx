import { useEffect } from 'react'

interface BlankContextMenuProps {
  x: number
  y: number
  selectMode: boolean
  classifying: boolean
  onClose: () => void
  onSelect: () => void
  onAutoClassify: () => void
  onRefresh: () => void
}

const ITEM_CLASS =
  'flex w-full items-center gap-2 px-3.5 py-2 text-left text-[13px] text-stone-700 transition hover:bg-stone-100 disabled:cursor-not-allowed disabled:opacity-60'

export function BlankContextMenu({ x, y, selectMode, classifying, onClose, onSelect, onAutoClassify, onRefresh }: BlankContextMenuProps) {
  useEffect(() => {
    const close = () => onClose()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('click', close)
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
      window.removeEventListener('keydown', onKey)
    }
  }, [onClose])

  const left = Math.min(x, window.innerWidth - 190)
  const top = Math.min(y, window.innerHeight - 150)

  return (
    <div
      className="modal-panel fixed z-[90] w-44 overflow-hidden rounded-xl border border-stone-200 py-1 shadow-xl"
      style={{ left, top }}
      onClick={(event) => event.stopPropagation()}
      onContextMenu={(event) => event.preventDefault()}
    >
      <button type="button" onClick={onSelect} className={ITEM_CLASS}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-3.5 w-3.5">
          <path strokeLinecap="round" strokeLinejoin="round" d="M8 4h8a2 2 0 0 1 2 2v8l-6 6H8a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" />
          <path strokeLinecap="round" strokeLinejoin="round" d="m9.5 11.5 2 2 3.5-4" />
        </svg>
        {selectMode ? '退出选择' : '选择'}
      </button>
      <button type="button" disabled={classifying} onClick={onAutoClassify} className={ITEM_CLASS}>
        {classifying ? (
          <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-stone-300 border-t-teal-700" />
        ) : (
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-3.5 w-3.5">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M13 3l1.9 5.1L20 10l-5.1 1.9L13 17l-1.9-5.1L6 10l5.1-1.9L13 3ZM5 17l.9 2.1L8 20l-2.1.9L5 23l-.9-2.1L2 20l2.1-.9L5 17Z"
            />
          </svg>
        )}
        {classifying ? '分类中…' : '自动分类'}
      </button>
      <button type="button" onClick={onRefresh} className={ITEM_CLASS}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-3.5 w-3.5">
          <path strokeLinecap="round" strokeLinejoin="round" d="M20 11a8 8 0 1 0-2.3 6.3M20 5v6h-6" />
        </svg>
        刷新图片
      </button>
    </div>
  )
}
