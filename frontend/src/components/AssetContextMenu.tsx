import { useEffect } from 'react'

interface AssetContextMenuProps {
  x: number
  y: number
  onClose: () => void
  onMove: () => void
  onDelete: () => void
}

export function AssetContextMenu({ x, y, onClose, onMove, onDelete }: AssetContextMenuProps) {
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

  const left = Math.min(x, window.innerWidth - 180)
  const top = Math.min(y, window.innerHeight - 108)

  return (
    <div
      className="modal-panel fixed z-[90] w-40 overflow-hidden rounded-xl border border-stone-200 py-1 shadow-xl"
      style={{ left, top }}
      onClick={(event) => event.stopPropagation()}
      onContextMenu={(event) => event.preventDefault()}
    >
      <button
        type="button"
        onClick={onMove}
        className="flex w-full items-center gap-2 px-3.5 py-2 text-left text-[13px] text-stone-700 transition hover:bg-stone-100"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-3.5 w-3.5">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M4 7a2 2 0 0 1 2-2h3l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7Z"
          />
        </svg>
        移动到文件夹
      </button>
      <button
        type="button"
        onClick={onDelete}
        className="flex w-full items-center gap-2 px-3.5 py-2 text-left text-[13px] text-red-600 transition hover:bg-red-50"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-3.5 w-3.5">
          <path strokeLinecap="round" d="M5 7h14M10 7V5h4v2m-7 0 .8 12h8.4L17 7" />
        </svg>
        移入回收站
      </button>
    </div>
  )
}
