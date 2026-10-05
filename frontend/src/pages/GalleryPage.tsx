import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { AssetResponse, CharacterResponse, FolderResponse, PageResult } from '../api/types'
import { AssetThumb } from '../components/AssetThumb'
import { FolderDialog } from '../components/FolderDialog'
import { Pagination } from '../components/Pagination'
import { useUploads } from '../components/UploadProvider'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'
import { formatBytes } from '../utils/format'

const PAGE_SIZE = 20

interface Filters {
  filename: string
  author: string
  exact: boolean
  page: number
}

type FolderSelection = { type: 'all' } | { type: 'uncategorized' } | { type: 'folder'; id: number }

export function GalleryPage() {
  const toast = useToast()
  const confirm = useConfirm()
  const [inputs, setInputs] = useState({ filename: '', author: '', exact: false })
  const [filters, setFilters] = useState<Filters>({ filename: '', author: '', exact: false, page: 1 })
  const [selection, setSelection] = useState<FolderSelection>({ type: 'all' })
  const [folders, setFolders] = useState<FolderResponse[]>([])
  const [dialog, setDialog] = useState<{ open: boolean; folder: FolderResponse | null }>({ open: false, folder: null })
  const [data, setData] = useState<PageResult<AssetResponse> | null>(null)
  const [loading, setLoading] = useState(false)
  const [selectMode, setSelectMode] = useState(false)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [moveOpen, setMoveOpen] = useState(false)
  const [moveTarget, setMoveTarget] = useState('')
  const [markOpen, setMarkOpen] = useState(false)
  const [markTarget, setMarkTarget] = useState('')
  const [characters, setCharacters] = useState<CharacterResponse[]>([])
  const fileInput = useRef<HTMLInputElement>(null)
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

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const params = new URLSearchParams({ page: String(filters.page), size: String(PAGE_SIZE) })
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
      setData(await api.get<PageResult<AssetResponse>>(`${path}?${params.toString()}`))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [filters, selection, toast])

  useEffect(() => {
    void load()
  }, [load])

  const revisionRef = useRef(revision)
  useEffect(() => {
    if (revisionRef.current === revision) {
      return
    }
    revisionRef.current = revision
    setFilters((current) => ({ ...current, page: 1 }))
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

  const refresh = () => {
    setFilters((current) => ({ ...current }))
    void loadFolders()
  }

  const enterBatch = (target?: FolderSelection) => {
    if (target) {
      setSelection(target)
      setFilters((current) => ({ ...current, page: 1 }))
    }
    setSelected(new Set())
    setSelectMode(true)
  }

  const exitBatch = () => {
    setSelectMode(false)
    setSelected(new Set())
  }

  const toggleSelect = (id: number) => {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  const pageIds = data?.records.map((asset) => asset.id) ?? []
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
        assetIds: selectedIds(),
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

  const createFolder = async (name: string, description: string) => {
    setDialog({ open: false, folder: null })
    try {
      const created = await api.post<FolderResponse>('/api/folders', {
        name,
        description: description || undefined,
      })
      toast.success(`文件夹「${created.name}」已创建`)
      setSelection({ type: 'folder', id: created.id })
      setFilters((current) => ({ ...current, page: 1 }))
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
    setFilters({ ...inputs, page: 1 })
  }

  const resetSearch = () => {
    setInputs({ filename: '', author: '', exact: false })
    setFilters({ filename: '', author: '', exact: false, page: 1 })
  }

  const selectionKey = selection.type === 'folder' ? `folder-${selection.id}` : selection.type

  const folderRowClass = (active: boolean) =>
    `flex w-full items-center justify-between gap-2 rounded-lg px-2.5 py-2 text-sm transition ${
      active ? 'bg-stone-200/70 font-medium text-stone-900' : 'text-stone-600 hover:bg-stone-100'
    }`

  const batchButtonClass =
    'surface-solid rounded border border-stone-200 px-1.5 text-[10px] text-stone-500 shadow-sm transition hover:text-stone-900'

  return (
    <div className="grid gap-6 lg:grid-cols-[220px_minmax(0,1fr)]">
      <aside className="card sticky top-4 z-30 h-fit p-3">
        <div className="flex items-center justify-between px-2 py-1.5">
          <h2 className="text-sm font-semibold text-stone-800">文件夹</h2>
          <button
            type="button"
            onClick={() => setDialog({ open: true, folder: null })}
            className="text-xs text-stone-400 transition hover:text-stone-700"
          >
            + 新建
          </button>
        </div>
        <nav className="mt-1 space-y-0.5">
          <div className="group relative">
            <button
              type="button"
              onClick={() => {
                setSelection({ type: 'all' })
                setFilters((current) => ({ ...current, page: 1 }))
              }}
              className={folderRowClass(selectionKey === 'all')}
            >
              <span>全部图片</span>
            </button>
            <button
              type="button"
              title="批量操作"
              onClick={() => enterBatch({ type: 'all' })}
              className={`absolute right-1.5 top-2 hidden group-hover:block ${batchButtonClass}`}
            >
              批量
            </button>
          </div>
          <div className="group relative">
            <button
              type="button"
              onClick={() => {
                setSelection({ type: 'uncategorized' })
                setFilters((current) => ({ ...current, page: 1 }))
              }}
              className={folderRowClass(selectionKey === 'uncategorized')}
            >
              <span>未分类</span>
            </button>
            <button
              type="button"
              title="批量操作"
              onClick={() => enterBatch({ type: 'uncategorized' })}
              className={`absolute right-1.5 top-2 hidden group-hover:block ${batchButtonClass}`}
            >
              批量
            </button>
          </div>
          {folders.map((folder) => (
            <div key={folder.id} className="group relative">
              <button
                type="button"
                onClick={() => {
                  setSelection({ type: 'folder', id: folder.id })
                  setFilters((current) => ({ ...current, page: 1 }))
                }}
                className={folderRowClass(selectionKey === `folder-${folder.id}`)}
                title={folder.description ?? folder.name}
              >
                <span className="flex min-w-0 items-center gap-1.5">
                  <span className="truncate">{folder.name}</span>
                  {folder.autoCreated && <span className="chip shrink-0 px-1.5 py-0 text-[10px]">自动</span>}
                </span>
                <span className="shrink-0 text-xs text-stone-400">{folder.assetCount}</span>
              </button>
              <div className="absolute right-1.5 top-2 hidden gap-1 group-hover:flex">
                <button
                  type="button"
                  title="批量操作"
                  onClick={() => enterBatch({ type: 'folder', id: folder.id })}
                  className={batchButtonClass}
                >
                  批量
                </button>
                <button
                  type="button"
                  title="重命名"
                  onClick={() => setDialog({ open: true, folder })}
                  className={batchButtonClass}
                >
                  改名
                </button>
                <button
                  type="button"
                  title="删除"
                  onClick={() => void deleteFolder(folder)}
                  className="surface-solid rounded border border-stone-200 px-1.5 text-[10px] text-stone-500 shadow-sm transition hover:text-red-600"
                >
                  删除
                </button>
              </div>
            </div>
          ))}
          {folders.length === 0 && (
            <p className="px-2.5 py-2 text-xs leading-relaxed text-stone-400">
              还没有文件夹。上传时不选文件夹，Agent 会自动归类并按需新建。
            </p>
          )}
        </nav>
      </aside>

      <div className="min-w-0">
        <div className="card sticky top-4 z-20 p-4">
          <form onSubmit={applySearch} className="flex flex-wrap items-end gap-3">
            <label className="w-44">
              <span className="label">文件名</span>
              <input
                value={inputs.filename}
                onChange={(event) => setInputs((current) => ({ ...current, filename: event.target.value }))}
                placeholder="支持模糊匹配"
                className="input"
              />
            </label>
            <label className="w-36">
              <span className="label">作者</span>
              <input
                value={inputs.author}
                onChange={(event) => setInputs((current) => ({ ...current, author: event.target.value }))}
                placeholder="EXIF 或手动"
                className="input"
              />
            </label>
            <label className="flex h-9 cursor-pointer items-center gap-2 text-sm text-stone-600">
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

            <div className="ml-auto flex items-end gap-3">
              <div className="flex flex-col items-end gap-1">
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
                <span className="text-[11px] text-stone-400">
                  {selection.type === 'folder'
                    ? `将上传到「${folderName(selection.id)}」`
                    : '未选择文件夹 · Agent 自动分类'}
                </span>
              </div>
            </div>
          </form>

          {selectMode && (
            <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-stone-100 pt-3 text-sm">
              <span className="text-stone-500">已选 {selected.size} 张</span>
              <button type="button" className="btn-secondary btn-sm" onClick={toggleSelectAllPage}>
                {allPageSelected ? '取消本页全选' : '全选本页'}
              </button>
              <button
                type="button"
                className="btn-secondary btn-sm"
                disabled={selected.size === 0}
                onClick={() => {
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
              <button type="button" className="btn-secondary btn-sm ml-auto" onClick={exitBatch}>
                退出批量
              </button>
            </div>
          )}
        </div>

        <div className="mt-5 flex items-center justify-between text-sm text-stone-500">
          <span>共 {data?.total ?? 0} 张</span>
          {loading && (
            <span className="flex items-center gap-2">
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-stone-300 border-t-teal-700" />
              加载中
            </span>
          )}
        </div>

        {data && data.records.length === 0 && !loading ? (
          <div className="card mt-4 border-dashed py-20 text-center">
            <p className="text-stone-500">这里还没有图片</p>
            <p className="mt-1 text-sm text-stone-400">点击右上角「上传图片」开始建立你的图库</p>
          </div>
        ) : (
          <div className="mt-4 grid grid-cols-2 gap-5 sm:grid-cols-3 xl:grid-cols-4">
            {data?.records.map((asset, index) => {
              const cardClass =
                'card animate-fade-in-up group overflow-hidden transition duration-200 hover:-translate-y-0.5 hover:shadow-md'
              const body = (
                <>
                  <div className="relative aspect-[4/3] overflow-hidden bg-stone-100">
                    <AssetThumb
                      assetId={asset.id}
                      alt={asset.originalFilename}
                      className="h-full w-full object-cover transition duration-300 group-hover:scale-[1.03]"
                    />
                    {selectMode && (
                      <span
                        className={`absolute left-2 top-2 flex h-6 w-6 items-center justify-center rounded-full border text-xs font-semibold ${
                          selected.has(asset.id)
                            ? 'brand-accent border-transparent text-white'
                            : 'surface-solid border-stone-300 text-transparent'
                        }`}
                      >
                        ✓
                      </span>
                    )}
                  </div>
                  <div className="p-3.5">
                    <div className="truncate text-sm font-medium text-stone-800" title={asset.originalFilename}>
                      {asset.originalFilename}
                    </div>
                    <div className="mt-1.5 flex items-center justify-between text-xs text-stone-500">
                      <span className="truncate">{asset.author || '未署名'}</span>
                      <span className="shrink-0">{formatBytes(asset.sizeBytes)}</span>
                    </div>
                  </div>
                </>
              )
              return selectMode ? (
                <button
                  type="button"
                  key={asset.id}
                  onClick={() => toggleSelect(asset.id)}
                  className={`${cardClass} text-left`}
                  style={{
                    animationDelay: `${Math.min(index, 12) * 25}ms`,
                    boxShadow: selected.has(asset.id) ? '0 0 0 2px var(--accent)' : undefined,
                  }}
                >
                  {body}
                </button>
              ) : (
                <Link
                  key={asset.id}
                  to={`/assets/${asset.id}`}
                  className={cardClass}
                  style={{ animationDelay: `${Math.min(index, 12) * 25}ms` }}
                >
                  {body}
                </Link>
              )
            })}
          </div>
        )}

        {data && <Pagination page={data.page} pages={data.pages} onChange={(page) => setFilters((c) => ({ ...c, page }))} />}
      </div>

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
            <p className="mt-2 text-sm text-stone-500">将所选 {selected.size} 张图片移动到：</p>
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
