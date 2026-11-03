import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams, useLocation, Link } from 'react-router-dom'
import { api } from '../api/client'
import { fetchAssetDetail, invalidateAssetDetail } from '../api/assetDetailCache'
import { AssetThumb } from '../components/AssetThumb'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'
import type {
  AssetAnalysisResponse,
  AssetDetailResponse,
  AssetWebSource,
  CharacterResponse,
  FolderResponse,
  SimilarAsset,
} from '../api/types'
import { formatBytes, formatDate } from '../utils/format'

const statusClass: Record<string, string> = {
  PENDING: 'inline-flex items-center rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-700',
  DONE: 'chip-accent',
  FAILED: 'inline-flex items-center rounded-full bg-red-50 px-2.5 py-0.5 text-xs font-medium text-red-600',
  SKIPPED: 'chip',
}

type PerceptionFacetKey = 'mood' | 'style' | 'tone' | 'scene' | 'usability'

const perceptionFacetLabels: Array<[PerceptionFacetKey, string]> = [
  ['mood', '氛围'],
  ['style', '风格'],
  ['tone', '色调'],
  ['scene', '场景'],
  ['usability', '用途'],
]

export function AssetDetailPage() {
  const { id } = useParams()
  const assetId = Number(id)
  const navigate = useNavigate()
  const location = useLocation()
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
  const [source, setSource] = useState<AssetWebSource | null>(null)
  const [sourceLoading, setSourceLoading] = useState(false)
  const [similar, setSimilar] = useState<SimilarAsset[] | null>(null)
  const [similarLoading, setSimilarLoading] = useState(false)
  const [related, setRelated] = useState<SimilarAsset[] | null>(null)

  useEffect(() => {
    let alive = true
    setRelated(null)
    api
      .get<SimilarAsset[]>(`/api/assets/${assetId}/related?limit=12`)
      .then((items) => {
        if (alive) {
          setRelated(items)
        }
      })
      .catch(() => {
        if (alive) {
          setRelated([])
        }
      })
    return () => {
      alive = false
    }
  }, [assetId])
  const [addTarget, setAddTarget] = useState('')
  const [zoomed, setZoomed] = useState(false)

  const findSimilar = async () => {
    setSimilarLoading(true)
    try {
      setSimilar(await api.get<SimilarAsset[]>(`/api/assets/${assetId}/similar?limit=12`))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '查找相似失败')
    } finally {
      setSimilarLoading(false)
    }
  }

  const loadSource = useCallback(async () => {
    try {
      setSource(await api.get<AssetWebSource | null>(`/api/assets/${assetId}/source`))
    } catch {
      setSource(null)
    }
  }, [assetId])

  const lookupSource = async () => {
    setSourceLoading(true)
    try {
      setSource(await api.post<AssetWebSource>(`/api/assets/${assetId}/source-lookup`))
      toast.success('来源反查完成')
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '来源反查失败')
    } finally {
      setSourceLoading(false)
    }
  }

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
    void loadSource()
  }, [loadCharacters, loadFolders, loadSource])

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
    const calibrating = detail?.characterId != null
    try {
      await api.put(`/api/characters/assets/${assetId}`, { characterId: Number(selectedCharacter) })
      toast.success(calibrating ? '已校准为所选角色，原角色参考已移除' : '已标记角色，并加入该角色的参考向量')
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
    const calibrating = detail?.characterId != null
    try {
      await api.post('/api/characters', { name: newCharacterName.trim(), referenceAssetId: assetId })
      setNewCharacterName('')
      toast.success(calibrating ? '已创建新角色并完成校准' : '已创建角色并标记')
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

  const addToFolder = async () => {
    if (!addTarget) {
      return
    }
    try {
      await api.post('/api/folders/assets/add', { assetIds: [assetId], folderId: Number(addTarget) })
      toast.success('已添加文件夹')
      setAddTarget('')
      invalidateAssetDetail(assetId)
      await Promise.all([load(), loadFolders()])
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '添加失败')
    }
  }

  const removeFromFolder = async (folderId: number) => {
    try {
      await api.post('/api/folders/assets/remove', { assetIds: [assetId], folderId })
      toast.success('已移出文件夹')
      invalidateAssetDetail(assetId)
      await Promise.all([load(), loadFolders()])
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '移出失败')
    }
  }

  const folderName = (id: number) => folders.find((folder) => folder.id === id)?.name ?? `#${id}`

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

  useEffect(() => {
    if (!zoomed) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setZoomed(false)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [zoomed])

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

  const from = (location.state as { from?: string } | null)?.from
  const backLabel = from === 'search' ? '返回搜索' : '返回图库'
  const goBack = () => {
    if (from === 'search') {
      navigate('/agent')
      return
    }
    if (from === 'gallery') {
      navigate('/')
      return
    }
    const historyState = window.history.state as { idx?: number } | null
    if (historyState && typeof historyState.idx === 'number' && historyState.idx > 0) {
      navigate(-1)
      return
    }
    navigate('/')
  }

  return (
    <div>
      <button
        type="button"
        onClick={goBack}
        className="btn-secondary btn-sm mb-4 inline-flex items-center gap-1.5"
        title={backLabel}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3.5 w-3.5">
          <path strokeLinecap="round" strokeLinejoin="round" d="M15 6l-6 6 6 6" />
        </svg>
        {backLabel}
      </button>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
      <div>
        <div className="card overflow-hidden">
          <img
            src={detail.url}
            alt={detail.originalFilename}
            onClick={() => setZoomed(true)}
            title="点击放大，再次点击或按 Esc 关闭"
            className="max-h-[70vh] w-full cursor-zoom-in object-contain"
          />
        </div>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <button type="button" onClick={download} className="btn-primary">
            下载原图
          </button>
          <button type="button" onClick={() => void copyLink()} className="btn-secondary">
            复制外链
          </button>
          <button type="button" onClick={() => void findSimilar()} disabled={similarLoading} className="btn-secondary">
            {similarLoading ? '查找中…' : '找相似'}
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
              {analysis.perception && (
                <div className="space-y-1.5">
                  <p className="text-xs text-stone-400">
                    感性特征（特征聚合）
                    {analysis.perception.wallpaperScore > 0 && (
                      <span className="ml-2 font-medium text-amber-700">
                        壁纸适配度 {Math.round(analysis.perception.wallpaperScore * 100)}%
                      </span>
                    )}
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {perceptionFacetLabels.flatMap(([key, label]) =>
                      (analysis.perception?.[key] ?? []).map((value) => (
                        <span
                          key={`${key}-${value}`}
                          className="chip border border-stone-200/80 text-amber-700"
                        >
                          {label}·{value}
                        </span>
                      )),
                    )}
                  </div>
                </div>
              )}
            </div>
          ) : (
            <p className="mt-3 text-stone-400">暂无分析（未启用 AI 或尚未分析）</p>
          )}
        </div>

        {related && related.length > 0 && (
          <div className="card mt-6 p-5 text-sm">
            <div className="flex items-center justify-between">
              <h2 className="font-medium text-stone-800">相关图片</h2>
              <span className="text-[11px] text-stone-400">图谱邻居 · 双向稳定关系</span>
            </div>
            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3">
              {related.map((item) => (
                <Link
                  key={item.assetId}
                  to={`/assets/${item.assetId}`}
                  className="group overflow-hidden rounded-xl border border-stone-200/70 transition hover:border-stone-300"
                >
                  <AssetThumb
                    assetId={item.assetId}
                    alt={item.originalFilename}
                    className="aspect-[4/3] w-full bg-stone-100 object-cover transition duration-300 group-hover:scale-[1.03]"
                  />
                  <div className="flex items-center justify-between gap-1.5 px-2 py-1.5 text-[11px] text-stone-500">
                    <span className="truncate" title={item.originalFilename}>
                      {item.originalFilename}
                    </span>
                    <span className="chip shrink-0">{Math.round(item.similarity * 100)}%</span>
                  </div>
                </Link>
              ))}
            </div>
          </div>
        )}

        <div className="card mt-6 p-5 text-sm">
          <div className="flex items-center justify-between">
            <h2 className="flex items-center gap-2 font-medium text-stone-800">
              相似图片
              {similarLoading && (
                <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-stone-200 border-t-teal-700" />
              )}
            </h2>
            {similar && similar.length > 0 && (
              <button type="button" onClick={() => void findSimilar()} className="btn-secondary btn-sm">
                刷新
              </button>
            )}
          </div>
          {similar === null ? (
            <p className="mt-3 text-stone-400">点击「找相似」，在本地库中检索相似图片（不联网）。</p>
          ) : similar.length === 0 ? (
            <p className="mt-3 text-stone-400">
              未找到相似图片。若该图没有图像向量，可先点上方「重新分析」生成后再试。
            </p>
          ) : (
            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3">
              {similar.map((item) => (
                <Link
                  key={item.assetId}
                  to={`/assets/${item.assetId}`}
                  className="group overflow-hidden rounded-xl border border-stone-200/70 transition hover:border-stone-300"
                >
                  <AssetThumb
                    assetId={item.assetId}
                    alt={item.originalFilename}
                    className="aspect-[4/3] w-full bg-stone-100 object-cover transition duration-300 group-hover:scale-[1.03]"
                  />
                  <div className="flex items-center justify-between gap-1.5 px-2 py-1.5 text-[11px] text-stone-500">
                    <span className="truncate" title={item.originalFilename}>
                      {item.originalFilename}
                    </span>
                    <span className="chip-accent shrink-0">{Math.round(item.similarity * 100)}%</span>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </div>

        <div className="card mt-6 p-5 text-sm">
          <div className="flex items-center justify-between">
            <h2 className="flex items-center gap-2 font-medium text-stone-800">
              来源
              {sourceLoading && (
                <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-stone-200 border-t-teal-700" />
              )}
            </h2>
            <button
              type="button"
              onClick={() => void lookupSource()}
              disabled={sourceLoading}
              className="btn-secondary btn-sm"
            >
              {source && (source.status === 'FOUND' || source.status === 'NO_MATCH') ? '重新查找' : '查找来源'}
            </button>
          </div>
          {source && source.status === 'FOUND' ? (
            <div className="mt-3 space-y-2 text-stone-600">
              <div className="flex flex-wrap items-center gap-2">
                <span className="chip-accent">{source.sourceType ?? source.provider}</span>
                {source.similarity != null && (
                  <span className="chip">相似度 {Math.round(source.similarity * 100)}%</span>
                )}
                {source.title && <span className="text-stone-800">{source.title}</span>}
              </div>
              {source.author && <p>画师：{source.author}</p>}
              {source.sourceUrl && (
                <a
                  href={source.sourceUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="block truncate text-teal-700 hover:underline"
                >
                  {source.sourceUrl}
                </a>
              )}
              {source.characters.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  {source.characters.map((character) => (
                    <span key={character} className="chip">
                      {character}
                    </span>
                  ))}
                </div>
              )}
            </div>
          ) : source && source.status === 'QUOTA' ? (
            <p className="mt-3 text-amber-700">今日反查配额已用尽，已自动暂停，明天恢复</p>
          ) : source && source.status === 'FAILED' ? (
            <p className="mt-3 text-stone-400">反查失败，可重试</p>
          ) : source && source.status === 'NO_MATCH' ? (
            <p className="mt-3 text-stone-400">未找到匹配的图片来源</p>
          ) : (
            <p className="mt-3 text-stone-400">尚未反查。配置 SauceNAO key 后可自动/手动识别出处、画师与角色。</p>
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
          {detail.folderIds.length === 0 ? (
            <p className="mt-3 text-stone-400">未分类（支持多归属，可从下方添加或右键图片操作）</p>
          ) : (
            <div className="mt-3 flex flex-wrap gap-2">
              {detail.folderIds.map((id) => (
                <span key={id} className="chip-accent inline-flex items-center gap-1.5 pr-1">
                  {folderName(id)}
                  <button
                    type="button"
                    title="移出该文件夹"
                    onClick={() => void removeFromFolder(id)}
                    className="flex h-4 w-4 items-center justify-center rounded-full text-[11px] leading-none transition hover:bg-black/20"
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
          )}
          <div className="mt-3 flex gap-2">
            <select value={addTarget} onChange={(event) => setAddTarget(event.target.value)} className="input">
              <option value="">添加到文件夹…</option>
              {folders
                .filter((folder) => !detail.folderIds.includes(folder.id))
                .map((folder) => (
                  <option key={folder.id} value={folder.id}>
                    {folder.name}
                  </option>
                ))}
            </select>
            <button
              type="button"
              disabled={!addTarget}
              onClick={() => void addToFolder()}
              className="btn-secondary shrink-0"
            >
              添加
            </button>
          </div>
        </div>

        <div className="card p-5 text-sm">
          <div className="flex items-center justify-between gap-3">
            <h2 className="font-medium text-stone-800">角色</h2>
            {detail.characterId ? (
              <span className="flex items-center gap-2">
                <span className="chip-accent">{characterName}</span>
                {detail.characterConfidence != null && detail.characterConfidence < 1 && (
                  <span className="chip" title="自动识别置信度，识别有误请在下方校准">
                    识别 {Math.round(detail.characterConfidence * 100)}%
                  </span>
                )}
                <button type="button" onClick={() => void unmarkCharacter()} className="btn-secondary btn-sm">
                  取消标记
                </button>
              </span>
            ) : (
              <span className="chip">未标记</span>
            )}
          </div>
          <div className="mt-3 space-y-3">
            <div className="flex gap-2">
              <select
                value={selectedCharacter}
                onChange={(event) => setSelectedCharacter(event.target.value)}
                className="input"
              >
                <option value="">选择已有角色</option>
                {characters
                  .filter((character) => character.id !== detail.characterId)
                  .map((character) => (
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
                {detail.characterId ? '校准为' : '标记'}
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
                {detail.characterId ? '新建并校准' : '新建并标记'}
              </button>
            </div>
            <p className="text-xs text-stone-400">
              {detail.characterId
                ? '校准会把该图从原角色的参考库移除，并加入新角色的参考库（需已完成 AI 分析）'
                : '标记会将该图片的 AI 向量加入角色参考库，之后上传相似图片可自动匹配（需已完成 AI 分析）'}
            </p>
          </div>
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

      {zoomed && (
        <div
          className="animate-fade-in fixed inset-0 z-[85] flex cursor-zoom-out items-center justify-center bg-black/85 p-4 backdrop-blur-sm"
          onClick={() => setZoomed(false)}
          title="点击关闭"
        >
          <img
            src={detail.url}
            alt={detail.originalFilename}
            className="max-h-full max-w-full object-contain"
          />
        </div>
      )}
      </div>
    </div>
  )
}
