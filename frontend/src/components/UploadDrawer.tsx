export interface UploadItem {
  id: number
  name: string
  percent: number
  status: 'uploading' | 'done' | 'error'
  error?: string
}

interface UploadDrawerProps {
  items: UploadItem[]
  open: boolean
  onOpen: () => void
  onClose: () => void
  onClearFinished: () => void
}

export function UploadDrawer({ items, open, onOpen, onClose, onClearFinished }: UploadDrawerProps) {
  const hasItems = items.length > 0
  const uploading = items.filter((item) => item.status === 'uploading').length
  const failed = items.filter((item) => item.status === 'error').length
  const expanded = hasItems && open

  return (
    <>
      <button
        type="button"
        onClick={onOpen}
        aria-label="查看上传队列"
        aria-hidden={expanded}
        tabIndex={expanded ? -1 : 0}
        className={`brand-accent fixed bottom-6 right-6 z-40 flex h-14 w-14 items-center justify-center rounded-full text-white shadow-lg transition duration-200 hover:scale-105 ${
          !hasItems || expanded ? 'pointer-events-none scale-75 opacity-0' : 'scale-100 opacity-100'
        }`}
      >
        {uploading > 0 ? (
          <>
            <span className="text-base font-semibold">{uploading}</span>
            <span className="absolute inset-0 animate-ping rounded-full bg-white/20" />
          </>
        ) : failed > 0 ? (
          <span className="text-lg font-semibold">!</span>
        ) : (
          <span className="text-lg">✓</span>
        )}
      </button>

      <aside
        aria-hidden={!expanded}
        className={`fixed right-0 top-0 z-40 h-full w-80 transform transition-transform duration-300 ease-out ${
          expanded ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        <div className="surface-panel flex h-full flex-col border-l border-stone-200 shadow-2xl backdrop-blur">
          <div className="flex items-center justify-between border-b border-stone-100 px-5 py-4">
            <h2 className="text-sm font-semibold text-stone-800">
              上传队列
              {uploading > 0 && <span className="ml-2 text-xs font-normal text-stone-400">{uploading} 个进行中</span>}
            </h2>
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={onClearFinished}
                className="text-xs text-stone-400 transition hover:text-stone-700"
              >
                清除已完成
              </button>
              <button
                type="button"
                onClick={onClose}
                title="收起"
                aria-label="收起上传队列"
                className="text-stone-400 transition hover:text-stone-700"
              >
                <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M9 6l6 6-6 6" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>
            </div>
          </div>
          <ul className="flex-1 space-y-4 overflow-auto px-5 py-4">
            {items.map((item) => (
              <li key={item.id} className="animate-fade-in-up">
                <div className="flex items-center justify-between gap-3 text-xs">
                  <span className="truncate text-stone-600" title={item.name}>
                    {item.name}
                  </span>
                  <span
                    className={
                      item.status === 'error'
                        ? 'shrink-0 text-red-500'
                        : item.status === 'done'
                          ? 'shrink-0 text-teal-700'
                          : 'shrink-0 text-stone-500'
                    }
                  >
                    {item.status === 'error' ? item.error : item.status === 'done' ? '完成' : `${item.percent}%`}
                  </span>
                </div>
                <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-stone-100">
                  <div
                    className={`h-full rounded-full transition-all duration-300 ${
                      item.status === 'error' ? 'bg-red-400' : 'progress-accent'
                    }`}
                    style={{ width: `${item.percent}%` }}
                  />
                </div>
              </li>
            ))}
          </ul>
        </div>
      </aside>
    </>
  )
}
