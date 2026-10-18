import { memo, startTransition, useCallback, useEffect, useRef, useState, type FormEvent, type MouseEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { AssetResponse, CharacterResponse, FolderResponse, PageResult } from '../api/types'
import { AssetContextMenu } from '../components/AssetContextMenu'
import { BlankContextMenu } from '../components/BlankContextMenu'
import { AssetThumb } from '../components/AssetThumb'
import { useScrollHidden } from '../hooks/useScrollHidden'
import { useWheelHorizontalScroll } from '../hooks/useWheelHorizontalScroll'
import { FolderDialog } from '../components/FolderDialog'
import { useUploads } from '../components/UploadProvider'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'
import { formatBytes } from '../utils/format'

const PAGE_SIZE = 20

type SortValue = 'name' | 'name_desc' | 'created_desc' | 'created_asc' | 'size_desc'

const SORT_OPTIONS: { value: SortValue; label: string }[] = [
  { value: 'name', label: '文件名 A→Z' },
  { value: 'name_desc', label: '文件名 Z→A' },
  { value: 'created_desc', label: '最新上传' },
  { value: 'created_asc', label: '最早上传' },
  { value: 'size_desc', label: '最大文件' },
]

const SORT_STORAGE_KEY = 'lumina.gallerySort'

function loadSort(): SortValue {
  const stored = localStorage.getItem(SORT_STORAGE_KEY)
  return SORT_OPTIONS.some((option) => option.value === stored) ? (stored as SortValue) : 'name'
}

interface Filters {
  filename: string
  author: string
  exact: boolean
  sort: SortValue
}

type FolderSelection = { type: 'all' } | { type: 'uncategorized' } | { type: 'folder'; id: number }

function SearchIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-4 w-4">
      <circle cx="11" cy="11" r="7" />
      <path strokeLinecap="round" d="m20 20-3.5-3.5" />
    </svg>
  )
}

function PlusIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3.5 w-3.5">
      <path strokeLinecap="round" d="M12 5v14M5 12h14" />
    </svg>
  )
}

const WINDOW_AHEAD = 20

/**
 * 动态请求尺寸：按当前视口覆盖的“整行(列数对齐) + 屏外 20 张”计算，
 * 保证一次请求就能填满屏幕并带 20 张缓冲，底部不再缺行。
 */
function computeInitialPageSize() {
  const width = Math.min(typeof window === 'undefined' ? 1280 : window.innerWidth, 1536) - 48
  const cols = width >= 1488 ? 6 : width >= 1232 ? 5 : width >= 976 ? 4 : width >= 592 ? 3 : 2
  const cardWidth = (width - (cols - 1) * 16) / cols
  const cardHeight = cardWidth * 0.75 + 52 + 16
  const viewportHeight = typeof window === 'undefined' ? 800 : window.innerHeight
  const rows = Math.max(1, Math.ceil(viewportHeight / cardHeight))
  const needed = rows * cols + WINDOW_AHEAD
  return Math.max(PAGE_SIZE, Math.min(120, Math.ceil(needed / cols) * cols))
}

interface GalleryCardProps {
  asset: AssetResponse
  index: number
  inWindow: boolean
  onScreen: boolean | undefined
  selectMode: boolean
  selected: boolean
  onToggle: (id: number) => void
  onContextMenu: (event: MouseEvent<HTMLElement>, asset: AssetResponse) => void
}

/**
 * 计数制加载窗口 + 纯视觉进出：
 * - inWindow 由父级按“屏幕内 + 屏外 20 张”动态计算；不在窗口内的卡片不加载图片（省流，不卸载已加载的）；
 * - 进屏的卡片按序逐张浮现；屏外窗口内的卡片整体浮现（无错峰）；
 * - onScreen=false 时卡片“陷入”（纯视觉），回到屏内“浮入”，不重新请求。
 */
const GalleryCard = memo(function GalleryCard({ asset, index, inWindow, onScreen, selectMode, selected, onToggle, onContextMenu }: GalleryCardProps) {
  const [node, setNode] = useState<HTMLElement | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [delay, setDelay] = useState(0)
  const [animating, setAnimating] = useState(false)

  const ref = useCallback((element: HTMLElement | null) => {
    setNode(element)
  }, [])

  useEffect(() => {
    if (!inWindow || loaded) {
      return
    }
    const rect = node?.getBoundingClientRect()
    const visible = rect ? rect.top < window.innerHeight && rect.bottom > 0 : false
    setDelay(visible ? Math.min(index, 12) * 25 : 0)
    setLoaded(true)
    setAnimating(true)
  }, [inWindow, loaded, node, index])

  const sunk = loaded && onScreen === false
  const cardClass = `media-card group block${selected ? ' media-card-selected' : ''}${
    animating ? ' animate-rise-in' : ''
  }${!loaded ? ' pointer-events-none opacity-0' : sunk ? ' media-card-sunk' : ''}`
  const body = (
    <div className="relative aspect-[4/3] overflow-hidden bg-stone-100">
      {loaded && (
        <AssetThumb
          assetId={asset.id}
          alt={asset.originalFilename}
          className="h-full w-full object-cover transition duration-500 ease-out group-hover:scale-[1.05]"
        />
      )}
      {loaded && (
        <>
          <div className="media-overlay pointer-events-none absolute inset-x-0 bottom-0 px-3 pb-2.5 pt-10">
            <div className="truncate text-[13px] font-medium leading-tight text-white" title={asset.originalFilename}>
              {asset.originalFilename}
            </div>
            <div className="mt-0.5 flex items-center gap-1.5 text-[11px] text-white/70 opacity-0 transition-opacity duration-300 group-hover:opacity-100">
              <span className="truncate">{asset.author || '未署名'}</span>
              <span>·</span>
              <span className="shrink-0 tabular-nums">{formatBytes(asset.sizeBytes)}</span>
            </div>
          </div>
          {selectMode && (
            <span
              className={`absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded-full text-[13px] font-semibold transition duration-200 ${
                selected
                  ? 'brand-accent border-2 border-transparent text-white'
                  : 'border-2 border-white/90 bg-black/15 text-transparent shadow-[0_1px_6px_rgba(0,0,0,0.45)]'
              }`}
            >
              ✓
            </span>
          )}
        </>
      )}
    </div>
  )
  const style = { animationDelay: `${delay}ms` }
  const handleAnimationEnd = () => setAnimating(false)
  if (selectMode) {
    return (
      <button
        ref={ref}
        type="button"
        data-card-index={index}
        onClick={() => onToggle(asset.id)}
        onContextMenu={(event) => onContextMenu(event, asset)}
        onAnimationEnd={handleAnimationEnd}
        className={`${cardClass} text-left`}
        style={style}
      >
        {body}
      </button>
    )
  }
  return (
    <Link
      ref={ref}
      to={`/assets/${asset.id}`}
      state={{ from: 'gallery' }}
      data-card-index={index}
      className={cardClass}
      style={style}
      onContextMenu={(event) => onContextMenu(event, asset)}
      onAnimationEnd={handleAnimationEnd}
    >
      {body}
    </Link>
  )
})

export function GalleryPage() {
  const toast = useToast()
  const confirm = useConfirm()
  const [inputs, setInputs] = useState({ filename: '', author: '', exact: false })
  const [filters, setFilters] = useState<Filters>({ filename: '', author: '', exact: false, sort: loadSort() })
  const [selection, setSelection] = useState<FolderSelection>({ type: 'all' })
  const [folders, setFolders] = useState<FolderResponse[]>([])
  const [dialog, setDialog] = useState<{ open: boolean; folder: FolderResponse | null }>({ open: false, folder: null })
  const [items, setItems] = useState<AssetResponse[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [selectMode, setSelectMode] = useState(false)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [moveOpen, setMoveOpen] = useState(false)
  const [moveTarget, setMoveTarget] = useState('')
  const [markOpen, setMarkOpen] = useState(false)
  const [markTarget, setMarkTarget] = useState('')
  const [characters, setCharacters] = useState<CharacterResponse[]>([])
  const [moveIds, setMoveIds] = useState<number[]>([])
  const [contextMenu, setContextMenu] = useState<{ asset: AssetResponse; x: number; y: number } | null>(null)
  const [blankMenu, setBlankMenu] = useState<{ x: number; y: number } | null>(null)
  const [classifying, setClassifying] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const sentinelRef = useRef<HTMLDivElement | null>(null)
  const loadingMoreRef = useRef(false)
  const gridRef = useRef<HTMLDivElement | null>(null)
  const toolbarRef = useRef<HTMLDivElement | null>(null)
  const toolbarHidden = useScrollHidden(toolbarRef)
  const visibleCardsRef = useRef(new Map<number, boolean>())
  const windowObserverRef = useRef<IntersectionObserver | null>(null)
  const observedNodesRef = useRef(new Map<number, Element>())
  const chipsRowRef = useRef<HTMLDivElement | null>(null)
  useWheelHorizontalScroll(chipsRowRef)
  const [visibility, setVisibility] = useState<Map<number, boolean>>(() => new Map())
  const [pageSize] = useState(computeInitialPageSize)
  const [loadLimit, setLoadLimit] = useState(pageSize)
  const { uploadFiles, uploading, revision } = useUploads()

  const loadFolders = useCallback(async () => {
    try {
      setFolders(await api.get<FolderResponse[]>('/api/folders'))
    } catch {
      setFolders([])
    }
  }, [])

  useEffect(() => {
    void loadFolders()
  }, [loadFolders])

  const buildPath = useCallback(
    (targetPage: number) => {
      const params = new URLSearchParams({ page: String(targetPage), size: String(pageSize), sort: filters.sort })
      let path = '/api/assets'
      if (filters.filename || filters.author) {
        path = '/api/assets/search'
        if (filters.filename) {
          params.set('filename', filters.filename)
        }
        if (filters.author) {
          params.set('author', filters.author)
        }
        if (filters.exact) {
          params.set('exact', 'true')
        }
      }
      if (selection.type === 'folder') {
        params.set('folderId', String(selection.id))
      } else if (selection.type === 'uncategorized') {
        params.set('uncategorized', 'true')
      }
      return `${path}?${params.toString()}`
    },
    [filters, selection, pageSize],
  )

  const fetchPage = useCallback(
    async (targetPage: number, mode: 'replace' | 'append') => {
      if (mode === 'append') {
        loadingMoreRef.current = true
        setLoadingMore(true)
      } else {
        setLoading(true)
      }
      try {
        const result = await api.get<PageResult<AssetResponse>>(buildPath(targetPage))
        if (mode === 'append') {
          // 追加渲染放入 transition：React 可中断切片，避免滚动时的长任务卡顿
          startTransition(() => {
            setTotal(result.total)
            setItems((current) => {
              const seen = new Set(current.map((item) => item.id))
              return [...current, ...result.records.filter((item) => !seen.has(item.id))]
            })
            setPage(targetPage)
            setHasMore(targetPage < result.pages)
          })
        } else {
          setTotal(result.total)
          setItems(result.records)
          visibleCardsRef.current.clear()
          setVisibility(new Map())
          setLoadLimit(pageSize)
          setPage(targetPage)
          setHasMore(targetPage < result.pages)
        }
      } catch (err) {
        toast.error(err instanceof Error ? err.message : '加载失败')
      } finally {
        if (mode === 'append') {
          setLoadingMore(false)
          loadingMoreRef.current = false
        } else {
          setLoading(false)
        }
      }
    },
    [buildPath, toast],
  )

  useEffect(() => {
    void fetchPage(1, 'replace')
  }, [fetchPage])

  // 空闲预取：首屏稳定后静默加载下一页，并把加载窗口扩到两页，
  // 让下一批缩略图在空闲时完成加载/解码，滚动时无网络无解码、无卡顿
  useEffect(() => {
    if (!hasMore || loading || items.length === 0 || items.length > pageSize) {
      return
    }
    const timer = window.setTimeout(() => {
      if (loadingMoreRef.current) {
        return
      }
      void fetchPage(page + 1, 'append').then(() => {
        startTransition(() => {
          setLoadLimit((current) => Math.max(current, pageSize * 2))
        })
      })
    }, 1200)
    return () => window.clearTimeout(timer)
  }, [hasMore, loading, items.length, pageSize, page, fetchPage])

  useEffect(() => {
    const node = sentinelRef.current
    if (!node || !hasMore) {
      return
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting) && !loadingMoreRef.current) {
          void fetchPage(page + 1, 'append')
        }
      },
      { rootMargin: '1200px 0px', threshold: 0 },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [page, hasMore, fetchPage])

  // 动态加载窗口：屏幕内 + 屏外 20 张；窗口随滚动推进，已加载的不再卸载
  // 持久观察者 + 增量挂载：追加新卡片时不重建整批观察，避免滚动加载时的卡顿
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = visibleCardsRef.current
        for (const entry of entries) {
          const index = Number((entry.target as HTMLElement).dataset.cardIndex)
          if (!Number.isNaN(index)) {
            visible.set(index, entry.isIntersecting)
          }
        }
        let lastVisible = -1
        visible.forEach((isVisible, index) => {
          if (isVisible && index > lastVisible) {
            lastVisible = index
          }
        })
        const grid = gridRef.current
        let needed = 0
        if (grid) {
          const tracks = getComputedStyle(grid).gridTemplateColumns.split(' ').filter(Boolean).length
          const cols = Math.max(1, tracks || 5)
          needed = Math.ceil((lastVisible + 1 + WINDOW_AHEAD) / cols) * cols
        }
        // 可见性/窗口更新放入 transition：滚动时切片渲染，避免长任务
        startTransition(() => {
          setVisibility(new Map(visible))
          if (needed > 0) {
            setLoadLimit((current) => Math.max(current, needed, pageSize))
          }
        })
      },
      // 提前 800px 纳入加载窗口：缩略图在屏外加载/解码，滚动到眼前时已就绪
      { rootMargin: '800px 0px', threshold: 0 },
    )
    windowObserverRef.current = observer
    return () => {
      observer.disconnect()
      windowObserverRef.current = null
      observedNodesRef.current.clear()
    }
  }, [pageSize])

  useEffect(() => {
    const observer = windowObserverRef.current
    const grid = gridRef.current
    if (!observer || !grid) {
      return
    }
    const nodes = [...grid.querySelectorAll<HTMLElement>('[data-card-index]')]
    const present = new Set<Element>(nodes)
    observedNodesRef.current.forEach((node, index) => {
      if (!present.has(node)) {
        observer.unobserve(node)
        observedNodesRef.current.delete(index)
      }
    })
    for (const node of nodes) {
      const index = Number(node.dataset.cardIndex)
      if (!Number.isNaN(index) && !observedNodesRef.current.has(index)) {
        observedNodesRef.current.set(index, node)
        observer.observe(node)
      }
    }
  }, [items])

  // 下滑超过阈值自动隐藏工具栏，回滑浮入（与智能搜索卡片共用 useScrollHidden）
  const revisionRef = useRef(revision)
  useEffect(() => {
    if (revisionRef.current === revision) {
      return
    }
    revisionRef.current = revision
    setInputs({ filename: '', author: '', exact: false })
    setSelection({ type: 'all' })
    setFilters((current) => ({ filename: '', author: '', exact: false, sort: current.sort }))
    void loadFolders()
  }, [revision, loadFolders])

  const folderName = (id: number) => folders.find((folder) => folder.id === id)?.name ?? `#${id}`

  const onPickFiles = (files: FileList | null) => {
    if (!files || files.length === 0) {
      return
    }
    uploadFiles(files, selection.type === 'folder' ? { id: selection.id, name: folderName(selection.id) } : undefined)
    if (fileInput.current) {
      fileInput.current.value = ''
    }
  }

  const refresh = async () => {
    void loadFolders()
    const loadedPages = page
    try {
      const collected: AssetResponse[] = []
      let newTotal = 0
      let pages = 1
      for (let target = 1; target <= loadedPages; target++) {
        const result = await api.get<PageResult<AssetResponse>>(buildPath(target))
        collected.push(...result.records)
        newTotal = result.total
        pages = result.pages
        if (target >= result.pages) {
          break
        }
      }
      setItems(collected)
      setTotal(newTotal)
      setPage(Math.min(loadedPages, pages))
      setHasMore(loadedPages < pages)
      return true
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '刷新失败')
      return false
    }
  }

  const selectFolder = (target: FolderSelection) => {
    setSelection(target)
  }

  const enterBatch = (target?: FolderSelection) => {
    if (target) {
      selectFolder(target)
    }
    setSelected(new Set())
    setSelectMode(true)
  }

  const exitBatch = () => {
    setSelectMode(false)
    setSelected(new Set())
  }

  const toggleSelect = useCallback((id: number) => {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }, [])

  const pageIds = items.map((asset) => asset.id)
  const allPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))

  const toggleSelectAllPage = () => {
    setSelected((current) => {
      const next = new Set(current)
      if (allPageSelected) {
        pageIds.forEach((id) => next.delete(id))
      } else {
        pageIds.forEach((id) => next.add(id))
      }
      return next
    })
  }

  const selectedIds = () => Array.from(selected)

  const batchMove = async () => {
    try {
      const count = await api.put<number>('/api/folders/assets', {
        assetIds: moveIds,
        folderId: moveTarget ? Number(moveTarget) : null,
      })
      toast.success(moveTarget ? `已移动 ${count} 张到「${folderName(Number(moveTarget))}」` : `已将 ${count} 张移至未分类`)
      setMoveOpen(false)
      exitBatch()
      refresh()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '移动失败')
    }
  }

  const deleteSingle = async (asset: AssetResponse) => {
    const confirmed = await confirm({
      title: '移入回收站',
      message: `将「${asset.originalFilename}」移入回收站？可在保留期内恢复。`,
      confirmText: '移入回收站',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      await api.del(`/api/assets/${asset.id}`)
      toast.success('已移入回收站')
      refresh()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    }
  }

  const openContextMenu = useCallback((event: MouseEvent<HTMLElement>, asset: AssetResponse) => {
    event.preventDefault()
    event.stopPropagation()
    setBlankMenu(null)
    setContextMenu({ asset, x: event.clientX, y: event.clientY })
  }, [])

  useEffect(() => {
    const main = document.querySelector('main')
    if (!main) {
      return
    }
    const handler = (event: globalThis.MouseEvent) => {
      const target = event.target as HTMLElement
      if (target.closest('a, button, input, select, textarea, label, .card, aside, [role="dialog"], [data-blank-menu="off"]')) {
        return
      }
      event.preventDefault()
      setContextMenu(null)
      setBlankMenu({ x: event.clientX, y: event.clientY })
    }
    main.addEventListener('contextmenu', handler)
    return () => main.removeEventListener('contextmenu', handler)
  }, [])

  const autoClassifyAll = async () => {
    setBlankMenu(null)
    if (classifying) {
      return
    }
    const confirmed = await confirm({
      title: '自动分类未分类图片',
      message: '将根据 AI 分析结果把所有「未分类」图片归入已有文件夹（必要时新建），可能需要一些时间。',
      confirmText: '开始分类',
    })
    if (!confirmed) {
      return
    }
    setClassifying(true)
    try {
      const ids: number[] = []
      for (let target = 1; target <= 50; target++) {
        const result = await api.get<PageResult<AssetResponse>>(`/api/assets?page=${target}&size=100&uncategorized=true`)
        ids.push(...result.records.map((record) => record.id))
        if (target >= result.pages) {
          break
        }
      }
      if (ids.length === 0) {
        toast.info('没有未分类的图片')
        return
      }
      let classified = 0
      for (let offset = 0; offset < ids.length; offset += 100) {
        classified += await api.post<number>('/api/folders/classify', { assetIds: ids.slice(offset, offset + 100) })
      }
      toast.success(
        classified > 0 ? `自动分类完成，${classified} 张已归类` : '分类未生效：图片可能缺少 AI 分析结果或 AI 未启用',
      )
      void refresh()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '自动分类失败')
    } finally {
      setClassifying(false)
    }
  }

  const refreshImages = async () => {
    setBlankMenu(null)
    if (await refresh()) {
      toast.success('图片已刷新')
    }
  }

  const batchDelete = async () => {
    const confirmed = await confirm({
      title: '批量移入回收站',
      message: `将所选 ${selected.size} 张图片移入回收站？可在保留期内恢复。`,
      confirmText: '移入回收站',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      const count = await api.post<number>('/api/assets/batch/delete', { assetIds: selectedIds() })
      toast.success(`已移入回收站 ${count} 张`)
      exitBatch()
      refresh()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    }
  }

  const batchClassify = async () => {
    const confirmed = await confirm({
      title: 'Agent 批量分类',
      message: `将根据 AI 描述把所选 ${selected.size} 张重新归类（优先已有文件夹，必要时新建）。`,
      confirmText: '开始分类',
    })
    if (!confirmed) {
      return
    }
    try {
      const count = await api.post<number>('/api/folders/classify', { assetIds: selectedIds() })
      toast.success(count > 0 ? `已分类 ${count} 张` : '所选图片缺少 AI 分析结果，无法分类')
      exitBatch()
      refresh()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '批量分类失败')
    }
  }

  const openMarkDialog = async () => {
    try {
      setCharacters(await api.get<CharacterResponse[]>('/api/characters'))
      setMarkTarget('')
      setMarkOpen(true)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '加载角色失败')
    }
  }

  const batchMark = async () => {
    if (!markTarget) {
      return
    }
    try {
      const count = await api.put<number>('/api/characters/assets', {
        assetIds: selectedIds(),
        characterId: Number(markTarget),
      })
      toast.success(`已标记 ${count} 张`)
      setMarkOpen(false)
      exitBatch()
      refresh()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '标记失败')
    }
  }

  const batchSourceLookup = async () => {
    try {
      const count = await api.post<number>('/api/assets/source-lookup', { assetIds: selectedIds() })
      toast.success(`已入队 ${count} 张来源反查，完成后可在详情页查看出处`)
      exitBatch()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '入队失败')
    }
  }

  const createFolder = async (name: string, description: string) => {
    setDialog({ open: false, folder: null })
    try {
      const created = await api.post<FolderResponse>('/api/folders', {
        name,
        description: description || undefined,
      })
      toast.success(`文件夹「${created.name}」已创建`)
      selectFolder({ type: 'folder', id: created.id })
      await loadFolders()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '创建失败')
    }
  }

  const updateFolder = async (name: string, description: string) => {
    const folder = dialog.folder
    setDialog({ open: false, folder: null })
    if (!folder) {
      return
    }
    try {
      await api.put(`/api/folders/${folder.id}`, { name, description: description || undefined })
      toast.success('文件夹已更新')
      await loadFolders()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '更新失败')
    }
  }

  const deleteFolder = async (folder: FolderResponse) => {
    const confirmed = await confirm({
      title: '删除文件夹',
      message: `删除「${folder.name}」？其中的 ${folder.assetCount} 张图片将变为未分类。`,
      confirmText: '删除',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      await api.del(`/api/folders/${folder.id}`)
      toast.success('文件夹已删除，图片已移至未分类')
      if (selection.type === 'folder' && selection.id === folder.id) {
        setSelection({ type: 'all' })
      }
      refresh()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    }
  }

  const applySearch = (event: FormEvent) => {
    event.preventDefault()
    setFilters((current) => ({ ...inputs, sort: current.sort }))
  }

  const resetSearch = () => {
    setInputs({ filename: '', author: '', exact: false })
    setFilters((current) => ({ filename: '', author: '', exact: false, sort: current.sort }))
  }

  const changeSort = (value: SortValue) => {
    localStorage.setItem(SORT_STORAGE_KEY, value)
    setFilters((current) => ({ ...current, sort: value }))
  }

  const selectionKey = selection.type === 'folder' ? `folder-${selection.id}` : selection.type

  const chipClass = (active: boolean) =>
    `group/chip flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-3 py-1.5 text-[13px] transition duration-200 ${
      active
        ? 'brand-accent border-transparent font-medium text-white shadow-sm'
        : 'surface-solid border-stone-200 text-stone-600 hover:border-stone-300 hover:text-stone-900'
    }`

  const miniAction = (active: boolean) =>
    `flex h-5 w-5 items-center justify-center rounded-full transition ${
      active ? 'bg-white/25 text-white hover:bg-white/40' : 'bg-stone-200/70 text-stone-500 hover:bg-stone-300 hover:text-stone-800'
    }`

  return (
    <div>
      <div
        ref={toolbarRef}
        className={`card sticky top-4 z-30 p-3.5 transition duration-300 ${
          toolbarHidden ? '-translate-y-[130%] opacity-0 pointer-events-none' : 'translate-y-0 opacity-100'
        }`}
      >
        <form onSubmit={applySearch} className="flex flex-wrap items-start gap-2.5">
          <label className="relative min-w-56 flex-1">
            <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-stone-400">
              <SearchIcon />
            </span>
            <input
              value={inputs.filename}
              onChange={(event) => setInputs((current) => ({ ...current, filename: event.target.value }))}
              placeholder="搜索文件名，支持模糊匹配"
              className="input pl-9"
            />
          </label>
          <label className="w-36">
            <input
              value={inputs.author}
              onChange={(event) => setInputs((current) => ({ ...current, author: event.target.value }))}
              placeholder="作者"
              className="input"
            />
          </label>
          <label className="flex h-9 cursor-pointer items-center gap-1.5 whitespace-nowrap text-[13px] text-stone-500">
            <input
              type="checkbox"
              checked={inputs.exact}
              onChange={(event) => setInputs((current) => ({ ...current, exact: event.target.checked }))}
              className="h-4 w-4 accent-teal-700"
            />
            精确匹配
          </label>
          <button type="submit" className="btn-primary">
            搜索
          </button>
          <button type="button" onClick={resetSearch} className="btn-secondary">
            重置
          </button>
          <button
            type="button"
            className="btn-secondary"
            onClick={() => (selectMode ? exitBatch() : enterBatch())}
          >
            {selectMode ? '退出批量' : '批量操作'}
          </button>
          <select
            value={filters.sort}
            onChange={(event) => changeSort(event.target.value as SortValue)}
            className="input w-32 cursor-pointer pr-7"
            title="排序方式"
          >
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>

          <div className="ml-auto flex items-center gap-2.5">
            <span className="whitespace-nowrap text-[11px] text-stone-400">
              {selection.type === 'folder' ? `上传到「${folderName(selection.id)}」` : '未选择文件夹 · Agent 自动分类'}
            </span>
            <input
              ref={fileInput}
              type="file"
              accept="image/*"
              multiple
              hidden
              onChange={(event) => onPickFiles(event.target.files)}
            />
            <button type="button" disabled={uploading} onClick={() => fileInput.current?.click()} className="btn-primary">
              {uploading ? '上传中…' : '上传图片'}
            </button>
          </div>
        </form>

        <div ref={chipsRowRef} className="mt-3 flex items-center gap-2 overflow-x-auto pb-0.5">
          <div className="relative shrink-0">
            <button type="button" onClick={() => selectFolder({ type: 'all' })} className={chipClass(selectionKey === 'all')}>
              <span>全部图片</span>
              <span className="hidden items-center pl-0.5 group-hover/chip:flex">
                <span
                  onClick={(event) => {
                    event.stopPropagation()
                    enterBatch({ type: 'all' })
                  }}
                  title="批量操作"
                  className={miniAction(selectionKey === 'all')}
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3 w-3">
                    <path strokeLinecap="round" strokeLinejoin="round" d="m5 13 4 4L19 7" />
                  </svg>
                </span>
              </span>
            </button>
          </div>
          <div className="relative shrink-0">
            <button
              type="button"
              onClick={() => selectFolder({ type: 'uncategorized' })}
              className={chipClass(selectionKey === 'uncategorized')}
            >
              <span>未分类</span>
              <span className="hidden items-center pl-0.5 group-hover/chip:flex">
                <span
                  onClick={(event) => {
                    event.stopPropagation()
                    enterBatch({ type: 'uncategorized' })
                  }}
                  title="批量操作"
                  className={miniAction(selectionKey === 'uncategorized')}
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3 w-3">
                    <path strokeLinecap="round" strokeLinejoin="round" d="m5 13 4 4L19 7" />
                  </svg>
                </span>
              </span>
            </button>
          </div>

          {folders.map((folder) => {
            const active = selectionKey === `folder-${folder.id}`
            return (
              <div key={folder.id} className="relative shrink-0">
                <button
                  type="button"
                  onClick={() => selectFolder({ type: 'folder', id: folder.id })}
                  className={chipClass(active)}
                  title={folder.description ?? folder.name}
                >
                  <span className="max-w-36 truncate">{folder.name}</span>
                  {folder.autoCreated && (
                    <span className={`hidden text-[10px] sm:inline ${active ? 'text-white/70' : 'text-stone-400'}`}>自动</span>
                  )}
                  <span className={`text-[11px] tabular-nums ${active ? 'text-white/75' : 'text-stone-400'}`}>
                    {folder.assetCount}
                  </span>
                  <span className="hidden items-center gap-1 pl-0.5 group-hover/chip:flex">
                    <span
                      onClick={(event) => {
                        event.stopPropagation()
                        enterBatch({ type: 'folder', id: folder.id })
                      }}
                      title="批量操作"
                      className={miniAction(active)}
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3 w-3">
                        <path strokeLinecap="round" strokeLinejoin="round" d="m5 13 4 4L19 7" />
                      </svg>
                    </span>
                    <span
                      onClick={(event) => {
                        event.stopPropagation()
                        setDialog({ open: true, folder })
                      }}
                      title="重命名"
                      className={miniAction(active)}
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3 w-3">
                        <path strokeLinecap="round" strokeLinejoin="round" d="M4 20h4l10-10-4-4L4 16v4Z" />
                        <path strokeLinecap="round" d="m13.5 6.5 4 4" />
                      </svg>
                    </span>
                    <span
                      onClick={(event) => {
                        event.stopPropagation()
                        void deleteFolder(folder)
                      }}
                      title="删除"
                      className={miniAction(active)}
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3 w-3">
                        <path strokeLinecap="round" d="M5 7h14M10 7V5h4v2m-7 0 .8 12h8.4L17 7" />
                      </svg>
                    </span>
                  </span>
                </button>
              </div>
            )
          })}

          <button
            type="button"
            onClick={() => setDialog({ open: true, folder: null })}
            className="surface-solid flex shrink-0 items-center gap-1 rounded-full border border-dashed border-stone-300 px-3 py-1.5 text-[13px] text-stone-500 transition hover:border-stone-400 hover:text-stone-800"
          >
            <PlusIcon />
            新建文件夹
          </button>
        </div>

        {selectMode && (
          <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-stone-200/60 pt-3 text-[13px]">
            <span className="text-stone-500">已选 {selected.size} 张</span>
            <button type="button" className="btn-secondary btn-sm" onClick={toggleSelectAllPage}>
              {allPageSelected ? '取消全选' : '全选已加载'}
            </button>
            <button
              type="button"
              className="btn-secondary btn-sm"
              disabled={selected.size === 0}
              onClick={() => {
                setMoveIds(selectedIds())
                setMoveTarget('')
                setMoveOpen(true)
              }}
            >
              移动到…
            </button>
            <button
              type="button"
              className="btn-secondary btn-sm"
              disabled={selected.size === 0}
              onClick={() => void openMarkDialog()}
            >
              标记角色…
            </button>
            <button
              type="button"
              className="btn-secondary btn-sm"
              disabled={selected.size === 0}
              onClick={() => void batchSourceLookup()}
            >
              查找来源
            </button>
            <button
              type="button"
              className="btn-secondary btn-sm"
              disabled={selected.size === 0}
              onClick={() => void batchClassify()}
            >
              Agent 分类
            </button>
            <button
              type="button"
              className="btn-danger btn-sm"
              disabled={selected.size === 0}
              onClick={() => void batchDelete()}
            >
              移入回收站
            </button>
          </div>
        )}
      </div>

      <div className="mt-4 flex items-center justify-between px-1 text-[13px] text-stone-500">
        <span>共 {total} 张</span>
        {loading && (
          <span className="flex items-center gap-2">
            <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-stone-300 border-t-teal-700" />
            加载中
          </span>
        )}
      </div>

      {items.length === 0 && !loading ? (
        <div className="card mt-3 border-dashed py-20 text-center">
          <p className="text-stone-500">这里还没有图片</p>
          <p className="mt-1 text-sm text-stone-400">点击右上角「上传图片」开始建立你的图库</p>
        </div>
      ) : (
        <div
          ref={gridRef}
          className="mt-3 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-6"
        >
          {items.map((asset, index) => (
            <GalleryCard
              key={asset.id}
              asset={asset}
              index={index}
              inWindow={index < loadLimit}
              onScreen={visibility.get(index)}
              selectMode={selectMode}
              selected={selected.has(asset.id)}
              onToggle={toggleSelect}
              onContextMenu={openContextMenu}
            />
          ))}
        </div>
      )}

      {items.length > 0 && (
        <div ref={sentinelRef} className="mt-6 flex min-h-8 items-center justify-center pb-4 text-xs text-stone-400">
          {loadingMore ? (
            <span className="flex items-center gap-2">
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-stone-300 border-t-teal-700" />
              加载中…
            </span>
          ) : !hasMore ? (
            <span>已加载全部 {total} 张</span>
          ) : null}
        </div>
      )}

      {contextMenu && (
        <AssetContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          onClose={() => setContextMenu(null)}
          onMove={() => {
            setMoveIds([contextMenu.asset.id])
            setMoveTarget('')
            setMoveOpen(true)
            setContextMenu(null)
          }}
          onDelete={() => {
            const asset = contextMenu.asset
            setContextMenu(null)
            void deleteSingle(asset)
          }}
        />
      )}

      {blankMenu && (
        <BlankContextMenu
          x={blankMenu.x}
          y={blankMenu.y}
          selectMode={selectMode}
          classifying={classifying}
          onClose={() => setBlankMenu(null)}
          onSelect={() => {
            setBlankMenu(null)
            if (selectMode) {
              exitBatch()
            } else {
              enterBatch()
            }
          }}
          onAutoClassify={() => void autoClassifyAll()}
          onRefresh={() => void refreshImages()}
        />
      )}

      <FolderDialog
        open={dialog.open}
        title={dialog.folder ? '重命名文件夹' : '新建文件夹'}
        initialName={dialog.folder?.name ?? ''}
        initialDescription={dialog.folder?.description ?? ''}
        submitText={dialog.folder ? '保存' : '创建'}
        onCancel={() => setDialog({ open: false, folder: null })}
        onSubmit={(name, description) => void (dialog.folder ? updateFolder(name, description) : createFolder(name, description))}
      />

      {moveOpen && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center px-4">
          <div className="modal-backdrop animate-fade-in absolute inset-0 backdrop-blur-sm" onClick={() => setMoveOpen(false)} />
          <div className="modal-panel scale-in relative w-full max-w-sm rounded-2xl border border-stone-200 p-6 shadow-2xl">
            <h3 className="text-base font-semibold text-stone-900">移动到文件夹</h3>
            <p className="mt-2 text-sm text-stone-500">将所选 {moveIds.length} 张图片移动到：</p>
            <select value={moveTarget} onChange={(event) => setMoveTarget(event.target.value)} className="input mt-4">
              <option value="">未分类</option>
              {folders.map((folder) => (
                <option key={folder.id} value={folder.id}>
                  {folder.name}
                </option>
              ))}
            </select>
            <div className="mt-6 flex justify-end gap-3">
              <button type="button" className="btn-secondary" onClick={() => setMoveOpen(false)}>
                取消
              </button>
              <button type="button" className="btn-primary" onClick={() => void batchMove()}>
                移动
              </button>
            </div>
          </div>
        </div>
      )}

      {markOpen && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center px-4">
          <div className="modal-backdrop animate-fade-in absolute inset-0 backdrop-blur-sm" onClick={() => setMarkOpen(false)} />
          <div className="modal-panel scale-in relative w-full max-w-sm rounded-2xl border border-stone-200 p-6 shadow-2xl">
            <h3 className="text-base font-semibold text-stone-900">标记角色</h3>
            <p className="mt-2 text-sm text-stone-500">将所选 {selected.size} 张图片标记为：</p>
            <select value={markTarget} onChange={(event) => setMarkTarget(event.target.value)} className="input mt-4">
              <option value="">选择角色</option>
              {characters.map((character) => (
                <option key={character.id} value={character.id}>
                  {character.name}
                </option>
              ))}
            </select>
            <div className="mt-6 flex justify-end gap-3">
              <button type="button" className="btn-secondary" onClick={() => setMarkOpen(false)}>
                取消
              </button>
              <button type="button" className="btn-primary" disabled={!markTarget} onClick={() => void batchMark()}>
                标记
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
