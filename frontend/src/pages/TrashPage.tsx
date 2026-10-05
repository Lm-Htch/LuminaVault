import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client'
import { invalidateAssetDetail } from '../api/assetDetailCache'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'
import type { AssetSettingsResponse, PageResult, TrashAssetResponse } from '../api/types'
import { Pagination } from '../components/Pagination'
import { formatBytes, formatDate } from '../utils/format'

const PAGE_SIZE = 20

export function TrashPage() {
  const confirm = useConfirm()
  const toast = useToast()
  const [page, setPage] = useState(1)
  const [data, setData] = useState<PageResult<TrashAssetResponse> | null>(null)
  const [retentionDays, setRetentionDays] = useState('')
  const [selectMode, setSelectMode] = useState(false)
  const [selected, setSelected] = useState<Set<number>>(new Set())

  const load = useCallback(async () => {
    try {
      const [trash, settings] = await Promise.all([
        api.get<PageResult<TrashAssetResponse>>(`/api/assets/trash?page=${page}&size=${PAGE_SIZE}`),
        api.get<AssetSettingsResponse>('/api/assets/settings'),
      ])
      setData(trash)
      setRetentionDays(String(settings.retentionDays))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '加载失败')
    }
  }, [page, toast])

  useEffect(() => {
    void load()
  }, [load])

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

  const restore = async (id: number) => {
    try {
      await api.post(`/api/assets/${id}/restore`)
      invalidateAssetDetail(id)
      toast.success('已恢复')
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '恢复失败')
    }
  }

  const purge = async (id: number) => {
    const confirmed = await confirm({
      title: '彻底删除',
      message: '彻底删除后不可恢复，确定继续？',
      confirmText: '彻底删除',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      await api.del(`/api/assets/${id}/purge`)
      toast.success('已彻底删除')
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    }
  }

  const batchRestore = async () => {
    const ids = Array.from(selected)
    try {
      const count = await api.post<number>('/api/assets/batch/restore', { assetIds: ids })
      ids.forEach(invalidateAssetDetail)
      toast.success(`已恢复 ${count} 张`)
      exitBatch()
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '批量恢复失败')
    }
  }

  const batchPurge = async () => {
    const confirmed = await confirm({
      title: '批量彻底删除',
      message: `彻底删除所选 ${selected.size} 张？此操作不可恢复。`,
      confirmText: '彻底删除',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    const ids = Array.from(selected)
    try {
      const count = await api.post<number>('/api/assets/batch/purge', { assetIds: ids })
      ids.forEach(invalidateAssetDetail)
      toast.success(`已彻底删除 ${count} 张`)
      const remaining = (data?.records.length ?? 0) - count
      exitBatch()
      if (remaining <= 0 && page > 1) {
        setPage(page - 1)
      } else {
        await load()
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '批量删除失败')
    }
  }

  const saveRetention = async (event: FormEvent) => {
    event.preventDefault()
    try {
      const settings = await api.put<AssetSettingsResponse>('/api/assets/settings', {
        retentionDays: Number(retentionDays),
      })
      setRetentionDays(String(settings.retentionDays))
      toast.success(`保留天数已更新为 ${settings.retentionDays} 天`)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '保存失败')
    }
  }

  return (
    <div>
      <div className="card flex flex-wrap items-center justify-between gap-4 p-5">
        <div>
          <h1 className="font-medium text-stone-800">回收站</h1>
          <p className="mt-1 text-xs text-stone-400">删除的图片在此保留指定天数，到期后自动彻底清理</p>
        </div>
        <div className="flex flex-wrap items-center gap-4">
          <button
            type="button"
            className="btn-secondary"
            onClick={() => (selectMode ? exitBatch() : setSelectMode(true))}
          >
            {selectMode ? '退出批量' : '批量操作'}
          </button>
          <form onSubmit={saveRetention} className="flex items-center gap-2 text-sm text-stone-500">
            <span>保留天数</span>
            <input
              type="number"
              min={1}
              max={3650}
              value={retentionDays}
              onChange={(event) => setRetentionDays(event.target.value)}
              className="input w-24"
            />
            <button type="submit" className="btn-secondary">
              保存
            </button>
          </form>
        </div>
      </div>

      {selectMode && (
        <div className="card mt-5 flex flex-wrap items-center gap-2 p-3 text-sm">
          <span className="text-stone-500">已选 {selected.size} 项</span>
          <button type="button" className="btn-secondary btn-sm" onClick={toggleSelectAllPage}>
            {allPageSelected ? '取消本页全选' : '全选本页'}
          </button>
          <button type="button" className="btn-secondary btn-sm" disabled={selected.size === 0} onClick={() => void batchRestore()}>
            批量恢复
          </button>
          <button type="button" className="btn-danger btn-sm" disabled={selected.size === 0} onClick={() => void batchPurge()}>
            批量彻底删除
          </button>
          <button type="button" className="btn-secondary btn-sm ml-auto" onClick={exitBatch}>
            退出批量
          </button>
        </div>
      )}

      <div className="card mt-5 overflow-hidden">
        <table className="w-full text-left text-sm">
          <thead className="bg-stone-50 text-xs uppercase tracking-wide text-stone-400">
            <tr>
              {selectMode && (
                <th className="w-10 px-5 py-3">
                  <input
                    type="checkbox"
                    checked={allPageSelected}
                    onChange={toggleSelectAllPage}
                    className="h-4 w-4 accent-teal-700"
                    aria-label="全选本页"
                  />
                </th>
              )}
              <th className="px-5 py-3 font-medium">文件名</th>
              <th className="px-5 py-3 font-medium">大小</th>
              <th className="px-5 py-3 font-medium">删除时间</th>
              <th className="px-5 py-3 font-medium">彻底删除时间</th>
              <th className="px-5 py-3 text-right font-medium">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-stone-100">
            {data?.records.map((asset) => (
              <tr
                key={asset.id}
                className={`transition hover:bg-stone-50/70 ${selectMode && selected.has(asset.id) ? 'bg-stone-50' : ''}`}
              >
                {selectMode && (
                  <td className="px-5 py-3.5">
                    <input
                      type="checkbox"
                      checked={selected.has(asset.id)}
                      onChange={() => toggleSelect(asset.id)}
                      className="h-4 w-4 accent-teal-700"
                      aria-label={`选择 ${asset.originalFilename}`}
                    />
                  </td>
                )}
                <td className="max-w-64 truncate px-5 py-3.5 text-stone-800" title={asset.originalFilename}>
                  {asset.originalFilename}
                </td>
                <td className="px-5 py-3.5 text-stone-500">{formatBytes(asset.sizeBytes)}</td>
                <td className="px-5 py-3.5 text-stone-500">{formatDate(asset.deletedAt)}</td>
                <td className="px-5 py-3.5 text-amber-700">{formatDate(asset.purgeAt)}</td>
                <td className="px-5 py-3.5">
                  <div className="flex justify-end gap-2">
                    <button type="button" onClick={() => void restore(asset.id)} className="btn-secondary btn-sm">
                      恢复
                    </button>
                    <button type="button" onClick={() => void purge(asset.id)} className="btn-danger btn-sm">
                      彻底删除
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {data && data.records.length === 0 && (
              <tr>
                <td colSpan={selectMode ? 6 : 5} className="px-5 py-16 text-center text-stone-400">
                  回收站是空的
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {data && <Pagination page={data.page} pages={data.pages} onChange={setPage} />}
    </div>
  )
}
