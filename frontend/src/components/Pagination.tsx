interface PaginationProps {
  page: number
  pages: number
  onChange: (page: number) => void
}

export function Pagination({ page, pages, onChange }: PaginationProps) {
  if (pages <= 1) {
    return null
  }
  return (
    <div className="flex items-center justify-center gap-4 py-8 text-sm">
      <button type="button" className="btn-secondary btn-sm" disabled={page <= 1} onClick={() => onChange(page - 1)}>
        上一页
      </button>
      <span className="text-stone-500">
        {page} / {pages}
      </span>
      <button type="button" className="btn-secondary btn-sm" disabled={page >= pages} onClick={() => onChange(page + 1)}>
        下一页
      </button>
    </div>
  )
}
