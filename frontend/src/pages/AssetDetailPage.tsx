import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { fetchAssetDetail, invalidateAssetDetail } from '../api/assetDetailCache'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'
import type { AssetAnalysisResponse, AssetDetailResponse, CharacterResponse, FolderResponse } from '../api/types'
import { formatBytes, formatDate } from '../utils/format'

const statusClass: Record<string, string> = {
  DONE: 'chip-accent',
  FAILED: 'inline-flex items-center rounded-full bg-red-50 px-2.5 py-0.5 text-xs font-medium text-red-600',
  SKIPPED: 'chip',
}

export function AssetDetailPage() {
  const { id } = useParams()
  const assetId = Number(id)
  const navigate = useNavigate()
  const confirm = useConfirm()
  const toast = useToast()
  const [detail, setDetail] = useState<AssetDetailResponse | null>(null)
  const [analysis, setAnalysis] = useState<AssetAnalysisResponse | null>(null)
  const [polling, setPolling] = useState(false)
  const [characters, setCharacters] = useState<CharacterResponse[]>([])
  const [folders, setFolders] = useState<FolderResponse[]>([])
  const [selectedCharacter, setSelectedCharacter] = useState('')
  const [newCharacterName, setNewCharacterName] = useState('')
  const [form, setForm] = useState({ originalFilename: '', author: '', description: '' })
  const [saving, setSaving] = useState(false)

  const loadAnalysis = useCallback(async () => {
    try {
      const next = await api.get<AssetAnalysisResponse>(`/api/agent/analysis/${assetId}`)
      setAnalysis(next)
      return next
    } catch {
      setAnalysis(null)
      return null
    }
  }, [assetId])

  const loadCharacters = useCallback(async () => {
    try {
      setCharacters(await api.get<CharacterResponse[]>('/api/characters'))
    } catch {
      setCharacters([])
    }
  }, [])

  const loadFolders = useCallback(async () => {
    try {
      setFolders(await api.get<FolderResponse[]>('/api/folders'))
    } catch {
      setFolders([])
    }
  }, [])

  const load = useCallback(async () => {
    try {
      const data = await fetchAssetDetail(assetId, true)
      setDetail(data)
      setForm({
        originalFilename: data.originalFilename,
        author: data.author ?? '',
        description: data.description ?? '',
      })
      const next = await loadAnalysis()
      if (next?.status === 'PENDING') {
        setPolling(true)
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '图片加载失败')
      navigate('/', { replace: true })
    }
  }, [assetId, loadAnalysis, navigate, toast])

  useEffect(() => {
    if (Number.isFinite(assetId)) {
      void load()
    }
  }, [assetId, load])

  useEffect(() => {
    void loadCharacters()
    void loadFolders()
  }, [loadCharacters, loadFolders])

  useEffect(() => {
    if (!polling) {
      return
    }
    let cancelled = false
    const startedAt = Date.now()
    const timer = window.setInterval(async () => {
      const next = await loadAnalysis()
      if (cancelled) {
        return
      }
      if (!next || ['DONE', 'FAILED', 'SKIPPED'].includes(next.status)) {
        setPolling(false)
        if (next?.status === 'DONE') {
          toast.success('AI 分析已完成')
          void load()
          void loadFolders()
        }
      } else if (Date.now() - startedAt > 90_000) {
        setPolling(false)
      }
    }, 2000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [polling, loadAnalysis, toast])

  const save = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    try {
      await api.put(`/api/assets/${assetId}`, form)
      invalidateAssetDetail(assetId)
      toast.success('已保存')
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const download = () => {
    window.open(`/f/${assetId}/download`, '_blank')
  }

  const copyLink = async () => {
    const shortUrl = `${window.location.origin}/f/${assetId}`
    try {
      await navigator.clipboard.writeText(shortUrl)
      toast.success(`外链已复制：${shortUrl}`)
    } catch {
      toast.info(`复制失败，请手动复制：${shortUrl}`)
    }
  }

  const remove = async () => {
    const confirmed = await confirm({
      title: '删除图片',
      message: '删除后图片将进入回收站，可在保留期内恢复。',
      confirmText: '删除',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      await api.del(`/api/assets/${assetId}`)
      invalidateAssetDetail(assetId)
      toast.success('已移入回收站')
      navigate('/', { replace: true })
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    }
  }

  const reanalyze = async () => {
    try {
      await api.post(`/api/agent/enrich/${assetId}`)
      toast.info('已提交后台分析，完成后自动刷新')
      setPolling(true)
      await loadAnalysis()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '触发失败')
    }
  }

  const markCharacter = async () => {
    if (!selectedCharacter) {
      return
    }
    try {
      await api.put(`/api/characters/assets/${assetId}`, { characterId: Number(selectedCharacter) })
      toast.success('已标记角色，并加入该角色的参考向量')
      invalidateAssetDetail(assetId)
      await Promise.all([load(), loadCharacters()])
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '标记失败')
    }
  }

  const createAndMarkCharacter = async () => {
    if (!newCharacterName.trim()) {
      return
    }
    try {
      await api.post('/api/characters', { name: newCharacterName.trim(), referenceAssetId: assetId })
      setNewCharacterName('')
      toast.success('已创建角色并标记')
      invalidateAssetDetail(assetId)
      await Promise.all([load(), loadCharacters()])
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '创建失败')
    }
  }

  const unmarkCharacter = async () => {
    try {
      await api.del(`/api/characters/assets/${assetId}`)
      toast.success('已取消角色标记')
      invalidateAssetDetail(assetId)
      await Promise.all([load(), loadCharacters()])
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '取消失败')
    }
  }

  const moveToFolder = async (folderId: number | null) => {
    try {
      await api.put(`/api/folders/assets/${assetId}`, { folderId })
      toast.success(folderId ? '已移动到文件夹' : '已移至未分类')
      invalidateAssetDetail(assetId)
      await Promise.all([load(), loadFolders()])
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '移动失败')
    }
  }

  const classifyAsset = async () => {
    try {
      await api.post(`/api/folders/classify/${assetId}`)
      toast.success('已自动分类')
      invalidateAssetDetail(assetId)
      await Promise.all([load(), loadFolders()])
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '自动分类失败')
    }
  }

  if (!detail) {
    return <div className="py-20 text-center text-stone-500">加载中…</div>
  }

  const metadata = detail.metadata
  const characterName = detail.characterId
    ? characters.find((character) => character.id === detail.characterId)?.name ?? `#${detail.characterId}`
    : null
  const typedFields: Array<[string, string | number | null | undefined]> = [
    ['作者', metadata?.author],
    ['版权', metadata?.copyright],
    ['相机', [metadata?.cameraMake, metadata?.cameraModel].filter(Boolean).join(' ') || null],
    ['镜头', metadata?.lensModel],
    ['软件', metadata?.software],
    ['拍摄时间', metadata?.dateTaken],
    [
      '拍摄地',
      metadata?.gpsLatitude != null && metadata?.gpsLongitude != null
        ? `${metadata.gpsLatitude.toFixed(5)}, ${metadata.gpsLongitude.toFixed(5)}`
        : null,
    ],
  ]

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
      <div>
        <div className="card overflow-hidden">
          <img src={detail.url} alt={detail.originalFilename} className="max-h-[70vh] w-full object-contain" />
        </div>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <button type="button" onClick={download} className="btn-primary">
            下载原图
          </button>
          <button type="button" onClick={() => void copyLink()} className="btn-secondary">
            复制外链
          </button>
          <button type="button" onClick={() => void remove()} className="btn-danger">
            删除
          </button>
        </div>

        <div className="card mt-6 p-5 text-sm">
          <div className="flex items-center justify-between">
            <h2 className="flex items-center gap-2 font-medium text-stone-800">
              AI 分析
              {polling && (
                <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-stone-200 border-t-teal-700" />
              )}
            </h2>
            <button type="button" onClick={() => void reanalyze()} className="btn-secondary btn-sm">
              重新分析
            </button>
          </div>
          {analysis && analysis.status !== 'NONE' ? (
            <div className="mt-3 space-y-2.5 text-stone-500">
              <div className="flex items-center gap-3">
                <span className={statusClass[analysis.status] ?? 'chip'}>{analysis.status}</span>
                {analysis.model && <span className="text-xs text-stone-400">{analysis.model}</span>}
              </div>
              {analysis.error && <p className="rounded-lg bg-red-50 px-3 py-2 text-red-600">{analysis.error}</p>}
              {analysis.description && <p>描述：{analysis.description}</p>}
              {analysis.tags.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  {analysis.tags.map((tag) => (
                    <span key={tag} className="chip">
                      {tag}
                    </span>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <p className="mt-3 text-stone-400">暂无分析（未启用 AI 或尚未分析）</p>
          )}
        </div>
      </div>

      <div className="space-y-6">
        <form onSubmit={save} className="card p-5">
          <h2 className="font-medium text-stone-800">编辑信息</h2>
          <label className="mt-4 block">
            <span className="label">文件名</span>
            <input
              value={form.originalFilename}
              onChange={(event) => setForm((current) => ({ ...current, originalFilename: event.target.value }))}
              className="input"
            />
          </label>
          <label className="mt-4 block">
            <span className="label">作者</span>
            <input
              value={form.author}
              onChange={(event) => setForm((current) => ({ ...current, author: event.target.value }))}
              className="input"
            />
          </label>
          <label className="mt-4 block">
            <span className="label">描述</span>
            <textarea
              value={form.description}
              onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
              rows={3}
              className="input resize-none"
            />
          </label>
          <button type="submit" disabled={saving} className="btn-primary mt-4">
            {saving ? '保存中…' : '保存'}
          </button>
        </form>

        <div className="card p-5 text-sm">
          <div className="flex items-center justify-between">
            <h2 className="font-medium text-stone-800">文件夹</h2>
            <button type="button" onClick={() => void classifyAsset()} className="btn-secondary btn-sm">
              Agent 自动分类
            </button>
          </div>
          <select
            value={detail.folderId ?? ''}
            onChange={(event) => void moveToFolder(event.target.value ? Number(event.target.value) : null)}
            className="input mt-3"
          >
            <option value="">未分类</option>
            {folders.map((folder) => (
              <option key={folder.id} value={folder.id}>
                {folder.name}
              </option>
            ))}
          </select>
        </div>

        <div className="card p-5 text-sm">
          <h2 className="font-medium text-stone-800">角色</h2>
          {detail.characterId ? (
            <div className="mt-3 flex items-center justify-between gap-3">
              <span className="chip-accent">{characterName}</span>
              <button type="button" onClick={() => void unmarkCharacter()} className="btn-secondary btn-sm">
                取消标记
              </button>
            </div>
          ) : (
            <div className="mt-3 space-y-3">
              <div className="flex gap-2">
                <select
                  value={selectedCharacter}
                  onChange={(event) => setSelectedCharacter(event.target.value)}
                  className="input"
                >
                  <option value="">选择已有角色</option>
                  {characters.map((character) => (
                    <option key={character.id} value={character.id}>
                      {character.name}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  disabled={!selectedCharacter}
                  onClick={() => void markCharacter()}
                  className="btn-primary shrink-0"
                >
                  标记
                </button>
              </div>
              <div className="flex gap-2">
                <input
                  value={newCharacterName}
                  onChange={(event) => setNewCharacterName(event.target.value)}
                  placeholder="新角色名，如 初音未来"
                  className="input"
                />
                <button
                  type="button"
                  disabled={!newCharacterName.trim()}
                  onClick={() => void createAndMarkCharacter()}
                  className="btn-secondary shrink-0"
                >
                  新建并标记
                </button>
              </div>
              <p className="text-xs text-stone-400">
                标记会将该图片的 AI 向量加入角色参考库，之后上传相似图片可自动匹配（需已完成 AI 分析）
              </p>
            </div>
          )}
        </div>

        <div className="card p-5 text-sm">
          <h2 className="font-medium text-stone-800">基本信息</h2>
          <dl className="mt-3 space-y-2.5 text-stone-500">
            <div className="flex justify-between gap-4">
              <dt>尺寸</dt>
              <dd className="text-stone-700">
                {detail.width && detail.height ? `${detail.width} × ${detail.height}` : '-'}
              </dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt>大小</dt>
              <dd className="text-stone-700">{formatBytes(detail.sizeBytes)}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt>类型</dt>
              <dd className="text-stone-700">{detail.contentType}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt>上传时间</dt>
              <dd className="text-stone-700">{formatDate(detail.createdAt)}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt>SHA-256</dt>
              <dd className="max-w-44 truncate font-mono text-xs text-stone-400" title={detail.sha256}>
                {detail.sha256}
              </dd>
            </div>
          </dl>
        </div>

        {metadata && (
          <div className="card p-5 text-sm">
            <h2 className="font-medium text-stone-800">图片元数据</h2>
            <dl className="mt-3 space-y-2.5 text-stone-500">
              {typedFields
                .filter(([, value]) => value !== null && value !== undefined && value !== '')
                .map(([label, value]) => (
                  <div key={label} className="flex justify-between gap-4">
                    <dt>{label}</dt>
                    <dd className="max-w-44 truncate text-stone-700" title={String(value)}>
                      {String(value)}
                    </dd>
                  </div>
                ))}
            </dl>
            {metadata.attributes && Object.keys(metadata.attributes).length > 0 && (
              <details className="mt-4 border-t border-stone-100 pt-3">
                <summary className="cursor-pointer text-stone-500 transition hover:text-stone-800">
                  全部属性（{Object.keys(metadata.attributes).length}）
                </summary>
                <dl className="mt-3 max-h-72 space-y-1.5 overflow-auto pr-2 text-xs">
                  {Object.entries(metadata.attributes).map(([key, value]) => (
                    <div key={key} className="flex justify-between gap-4">
                      <dt className="shrink-0 text-stone-400">{key}</dt>
                      <dd className="max-w-52 truncate text-stone-500" title={value}>
                        {value}
                      </dd>
                    </div>
                  ))}
                </dl>
              </details>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
