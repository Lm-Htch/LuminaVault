import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { FolderResponse } from '../api/types'
import { FolderDialog } from '../components/FolderDialog'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'
import { formatDate } from '../utils/format'

export function FoldersPage() {
  const toast = useToast()
  const confirm = useConfirm()
  const [folders, setFolders] = useState<FolderResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [dialog, setDialog] = useState<{ open: boolean; folder: FolderResponse | null }>({ open: false, folder: null })
  const [mergeSource, setMergeSource] = useState<FolderResponse | null>(null)
  const [mergeTarget, setMergeTarget] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    try {
      setFolders(await api.get<FolderResponse[]>('/api/folders'))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '加载文件夹失败')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void load()
  }, [load])

  const createOrUpdate = async (name: string, description: string) => {
    const folder = dialog.folder
    setDialog({ open: false, folder: null })
    try {
      if (folder) {
        await api.put(`/api/folders/${folder.id}`, { name, description })
        toast.success('文件夹已更新')
      } else {
        await api.post('/api/folders', { name, description })
        toast.success('文件夹已创建')
      }
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '保存失败')
    }
  }

  const remove = async (folder: FolderResponse) => {
    const confirmed = await confirm({
      title: '删除文件夹',
      message: `删除「${folder.name}」？其中 ${folder.assetCount} 张图片将变为未分类。`,
      confirmText: '删除',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      await api.del(`/api/folders/${folder.id}`)
      toast.success(`已删除「${folder.name}」，图片已退回未分类`)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    }
  }

  const merge = async () => {
    if (!mergeSource || !mergeTarget) {
      return
    }
    setSaving(true)
    try {
      const moved = await api.post<number>(`/api/folders/${mergeSource.id}/merge`, {
        targetFolderId: Number(mergeTarget),
      })
      const targetName = folders.find((folder) => folder.id === Number(mergeTarget))?.name ?? '目标文件夹'
      toast.success(`已将「${mergeSource.name}」的 ${moved} 张图片并入「${targetName}」`)
      setMergeSource(null)
      setMergeTarget('')
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '合并失败')
    } finally {
      setSaving(false)
    }
  }

  const mergeCandidates = mergeSource ? folders.filter((folder) => folder.id !== mergeSource.id) : []

  return (
    <div className="mx-auto max-w-4xl">
      <div className="card flex flex-wrap items-center justify-between gap-3 p-5">
        <div>
          <h1 className="text-base font-semibold text-stone-900">文件夹管理</h1>
          <p className="mt-1 text-xs text-stone-400">
            共 {folders.length} 个文件夹 · 删除或合并后图片会退回未分类，可在图库右键「自动分类」重新归类
          </p>
        </div>
        <button
          type="button"
          onClick={() => setDialog({ open: true, folder: null })}
          className="btn-primary"
        >
          新建文件夹
        </button>
      </div>

      <div className="card mt-4 overflow-hidden">
        {loading ? (
          <p className="px-5 py-10 text-center text-sm text-stone-400">加载中…</p>
        ) : folders.length === 0 ? (
          <p className="px-5 py-14 text-center text-sm text-stone-400">
            还没有文件夹。上传图片时开启自动分类，或点击右上角新建。
          </p>
        ) : (
          folders.map((folder) => (
            <div
              key={folder.id}
              className="flex flex-wrap items-center gap-3 border-b border-stone-100 px-5 py-3.5 last:border-b-0"
            >
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-sm font-medium text-stone-800" title={folder.name}>
                    {folder.name}
                  </span>
                  {folder.autoCreated && <span className="chip shrink-0">自动</span>}
                </div>
                <div className="mt-0.5 truncate text-xs text-stone-400" title={folder.description ?? ''}>
                  {folder.description || '无描述'} · 创建于 {formatDate(folder.createdAt)}
                </div>
              </div>
              <span className="shrink-0 text-sm tabular-nums text-stone-500">{folder.assetCount} 张</span>
              <div className="flex shrink-0 gap-2">
                <button
                  type="button"
                  onClick={() => setDialog({ open: true, folder })}
                  className="btn-secondary btn-sm"
                >
                  重命名
                </button>
                <button
                  type="button"
                  disabled={folders.length < 2}
                  onClick={() => {
                    setMergeSource(folder)
                    setMergeTarget('')
                  }}
                  className="btn-secondary btn-sm"
                >
                  合并
                </button>
                <button
                  type="button"
                  onClick={() => void remove(folder)}
                  className="btn-secondary btn-sm text-red-600 hover:text-red-700"
                >
                  删除
                </button>
              </div>
            </div>
          ))
        )}
      </div>

      <FolderDialog
        open={dialog.open}
        title={dialog.folder ? '重命名文件夹' : '新建文件夹'}
        initialName={dialog.folder?.name ?? ''}
        initialDescription={dialog.folder?.description ?? ''}
        submitText={dialog.folder ? '保存' : '创建'}
        onCancel={() => setDialog({ open: false, folder: null })}
        onSubmit={(name, description) => void createOrUpdate(name, description)}
      />

      {mergeSource && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center px-4">
          <div
            className="modal-backdrop animate-fade-in absolute inset-0 backdrop-blur-sm"
            onClick={() => setMergeSource(null)}
          />
          <div className="modal-panel scale-in relative w-full max-w-sm rounded-2xl border border-stone-200 p-6 shadow-2xl">
            <h3 className="text-base font-semibold text-stone-900">合并文件夹</h3>
            <p className="mt-2 text-sm text-stone-500">
              把「{mergeSource.name}」的 {mergeSource.assetCount} 张图片并入目标文件夹，随后删除该文件夹。
            </p>
            <select value={mergeTarget} onChange={(event) => setMergeTarget(event.target.value)} className="input mt-4">
              <option value="">选择目标文件夹</option>
              {mergeCandidates.map((folder) => (
                <option key={folder.id} value={folder.id}>
                  {folder.name}（{folder.assetCount} 张）
                </option>
              ))}
            </select>
            <div className="mt-6 flex justify-end gap-3">
              <button type="button" className="btn-secondary" onClick={() => setMergeSource(null)}>
                取消
              </button>
              <button type="button" disabled={!mergeTarget || saving} className="btn-primary" onClick={() => void merge()}>
                {saving ? '合并中…' : '合并'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
