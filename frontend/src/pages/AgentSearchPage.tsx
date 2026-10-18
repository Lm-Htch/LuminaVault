import { useCallback, useEffect, useRef, useState, type ClipboardEvent, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, tokenStore } from '../api/client'
import type { AgentSearchResponse, SearchFavoriteItem, SearchHistoryItem } from '../api/types'
import { AssetThumb } from '../components/AssetThumb'
import { SearchFlowPanel, type SearchFlowEvent } from '../components/SearchFlowPanel'
import { SimilarityGraphPanel, type GraphVisualizationData } from '../components/SimilarityGraphPanel'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'
import { useScrollHidden } from '../hooks/useScrollHidden'
import { useWheelHorizontalScroll } from '../hooks/useWheelHorizontalScroll'
import { formatDate, formatPercent } from '../utils/format'

const THRESHOLD_KEY = 'lumina.searchThreshold'

const STAGE_LABELS: Record<string, string> = {
  cache: '缓存',
  analyze: '意图',
  retrieve: '检索',
  rerank: '重排',
  vision: '视觉',
  done: '完成',
}

/**
 * 模块级搜索 store：搜索在后台持续进行（后端 SSE 推送真实进度），
 * 切换面板卸载页面不中断、不丢失，返回后从 store 恢复。
 */
interface SearchStore {
  query: string
  running: boolean
  progress: SearchFlowEvent[]
  result: AgentSearchResponse | null
  error: string | null
  completedAt: number
  toastShownError: string | null
  listeners: Set<() => void>
}

const searchStore: SearchStore = {
  query: '',
  running: false,
  progress: [],
  result: null,
  error: null,
  completedAt: 0,
  toastShownError: null,
  listeners: new Set(),
}

function notifySearchStore() {
  searchStore.listeners.forEach((listener) => listener())
}

async function streamSearch(query: string, minConfidence: number, attachmentId?: string | null): Promise<AgentSearchResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
  const access = tokenStore.access
  if (access) {
    headers.Authorization = `Bearer ${access}`
  }
  const response = await fetch('/api/agent/search/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({ query, limit: 12, minConfidence, ...(attachmentId ? { attachmentId } : {}) }),
  })
  if (!response.ok || !response.body) {
    throw new Error(`搜索失败（${response.status}）`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let result: AgentSearchResponse | null = null
  let streamError: string | null = null
  const handleFrame = (frame: string) => {
    let eventName = 'message'
    const dataLines: string[] = []
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    }
    if (dataLines.length === 0) {
      return
    }
    const data = dataLines.join('\n')
    if (eventName === 'progress') {
      try {
        searchStore.progress = [...searchStore.progress, JSON.parse(data) as SearchFlowEvent]
        notifySearchStore()
      } catch {
        // 忽略异常帧
      }
    } else if (eventName === 'result') {
      result = JSON.parse(data) as AgentSearchResponse
    } else if (eventName === 'error') {
      try {
        streamError = JSON.parse(data) as string
      } catch {
        streamError = data
      }
    }
  }
  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    let index = buffer.indexOf('\n\n')
    while (index >= 0) {
      handleFrame(buffer.slice(0, index))
      buffer = buffer.slice(index + 2)
      index = buffer.indexOf('\n\n')
    }
  }
  if (streamError) {
    throw new Error(streamError)
  }
  if (!result) {
    throw new Error('搜索未返回结果')
  }
  return result
}

function startSearch(query: string, minConfidence: number, attachment?: File | null) {
  searchStore.query = query
  searchStore.running = true
  searchStore.progress = []
  searchStore.result = null
  searchStore.error = null
  notifySearchStore()
  void (async () => {
    try {
      let attachmentId: string | null = null
      if (attachment) {
        searchStore.progress = [{ stage: 'analyze', message: '上传粘贴图片…' }]
        notifySearchStore()
        const form = new FormData()
        form.append('file', attachment)
        const uploaded = await api.upload<{ attachmentId: string }>('/api/agent/search/attachment', form)
        attachmentId = uploaded.attachmentId
      }
      const response = await streamSearch(query, minConfidence, attachmentId)
      searchStore.result = response
      searchStore.completedAt = Date.now()
    } catch (err) {
      searchStore.error = err instanceof Error ? err.message : '搜索失败'
    } finally {
      searchStore.running = false
      notifySearchStore()
    }
  })()
}

export function AgentSearchPage() {
  const toast = useToast()
  const confirm = useConfirm()
  const [query, setQuery] = useState(() => searchStore.query)
  const [version, setVersion] = useState(0)
  const [saving, setSaving] = useState(false)
  const [history, setHistory] = useState<SearchHistoryItem[]>([])
  const [favorites, setFavorites] = useState<SearchFavoriteItem[]>([])
  const [expanded, setExpanded] = useState<number | null>(null)
  const [commentExpanded, setCommentExpanded] = useState(false)
  const [attachment, setAttachment] = useState<{ file: File; url: string } | null>(null)
  const [graphOpen, setGraphOpen] = useState(true)
  const [threshold, setThreshold] = useState(() => {
    const raw = Number(localStorage.getItem(THRESHOLD_KEY))
    return Number.isFinite(raw) && raw > 0 && raw <= 0.95 ? raw : 0.4
  })

  const result = searchStore.result
  const loading = searchStore.running
  const progressEvents = searchStore.progress
  // 同类阶段仅保留一行并就地更新（如"视觉确认 x/17"），避免进度列表无限增长
  const stageRows: SearchFlowEvent[] = []
  const stageIndex = new Map<string, number>()
  for (const event of progressEvents) {
    const index = stageIndex.get(event.stage)
    if (index === undefined) {
      stageIndex.set(event.stage, stageRows.length)
      stageRows.push(event)
    } else {
      stageRows[index] = event
    }
  }
  const activeStage = progressEvents.length > 0 ? progressEvents[progressEvents.length - 1].stage : null
  const graphVisual = [...progressEvents].reverse().find((event) => event.stage === 'graph')?.detail as
    | GraphVisualizationData
    | undefined
  const [visibility, setVisibility] = useState<Map<number, boolean>>(() => new Map())
  const [animatingIds, setAnimatingIds] = useState<Set<number>>(() => new Set())
  const resultsRef = useRef<HTMLDivElement | null>(null)
  const searchCardRef = useRef<HTMLDivElement | null>(null)
  const graphPanelRef = useRef<HTMLDivElement | null>(null)
  const historyRowRef = useRef<HTMLDivElement | null>(null)
  useWheelHorizontalScroll(historyRowRef)
  // 吸顶卡片下滑隐藏/上滑浮回（与图库工具栏同一逻辑）
  const cardHidden = useScrollHidden(searchCardRef)

  const updateThreshold = (value: number) => {
    setThreshold(value)
    localStorage.setItem(THRESHOLD_KEY, String(value))
  }

  const visibleResults = result ? result.results.filter((item) => item.confidence >= threshold) : []
  const hiddenByThreshold = result ? result.results.length - visibleResults.length : 0

  const loadHistory = useCallback(async () => {
    try {
      setHistory(await api.get<SearchHistoryItem[]>('/api/agent/history'))
    } catch {
      setHistory([])
    }
  }, [])

  const loadFavorites = useCallback(async () => {
    try {
      setFavorites(await api.get<SearchFavoriteItem[]>('/api/agent/favorites'))
    } catch {
      setFavorites([])
    }
  }, [])

  useEffect(() => {
    void loadHistory()
    void loadFavorites()
  }, [loadHistory, loadFavorites])

  useEffect(() => {
    const listener = () => setVersion((current) => current + 1)
    searchStore.listeners.add(listener)
    return () => {
      searchStore.listeners.delete(listener)
    }
  }, [])

  useEffect(() => {
    if (!searchStore.running && searchStore.error && searchStore.toastShownError !== searchStore.error) {
      searchStore.toastShownError = searchStore.error
      toast.error(searchStore.error)
    }
  }, [version, toast])

  const completedRef = useRef(searchStore.completedAt)
  useEffect(() => {
    if (searchStore.completedAt !== completedRef.current) {
      completedRef.current = searchStore.completedAt
      void loadHistory()
    }
  }, [version, loadHistory])

  useEffect(() => {
    setAnimatingIds(new Set(result ? result.results.map((item) => item.assetId) : []))
  }, [result])

  // 与图库相同的浮入浮出逻辑：屏外结果浮现下沉，滚回浮入（纯视觉，不卸载）
  useEffect(() => {
    const container = resultsRef.current
    if (!container) {
      return
    }
    const nodes = [...container.querySelectorAll<HTMLElement>('[data-result-index]')]
    if (nodes.length === 0) {
      return
    }
    const observer = new IntersectionObserver(
      (entries) => {
        setVisibility((current) => {
          const next = new Map(current)
          for (const entry of entries) {
            const id = Number((entry.target as HTMLElement).dataset.resultIndex)
            if (!Number.isNaN(id)) {
              next.set(id, entry.isIntersecting)
            }
          }
          return next
        })
      },
      { threshold: 0 },
    )
    nodes.forEach((node) => observer.observe(node))
    return () => observer.disconnect()
  }, [result, threshold])

  const runSearch = (raw: string, file?: File | null) => {
    const trimmed = raw.trim()
    if (!trimmed && !file) {
      return
    }
    setQuery(trimmed)
    setGraphOpen(true)
    startSearch(trimmed, threshold, file)
  }

  const search = (event?: FormEvent) => {
    event?.preventDefault()
    runSearch(query, attachment?.file)
  }

  const toggleGraph = () => {
    setGraphOpen((current) => {
      const next = !current
      if (next) {
        window.setTimeout(() => {
          graphPanelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
        }, 80)
      }
      return next
    })
  }

  const pasteImage = (event: ClipboardEvent) => {
    const item = [...(event.clipboardData?.items ?? [])].find((entry) => entry.type.startsWith('image/'))
    const file = item?.getAsFile()
    if (!file) {
      return
    }
    event.preventDefault()
    const url = URL.createObjectURL(file)
    setAttachment((current) => {
      if (current) {
        URL.revokeObjectURL(current.url)
      }
      return { file, url }
    })
  }

  const removeAttachment = () => {
    setAttachment((current) => {
      if (current) {
        URL.revokeObjectURL(current.url)
      }
      return null
    })
  }

  const feedback = async (assetId: number, action: 'SELECT' | 'REJECT') => {
    try {
      await api.post('/api/agent/feedback', { assetId, action, query: result?.query ?? query.trim() })
      toast.success(
        action === 'SELECT' ? '已记录你的选择，后续排序会偏向它' : '已记录，后续将降低相似推荐',
      )
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '反馈失败')
    }
  }

  const saveCurrent = async () => {
    if (!result || visibleResults.length === 0) {
      return
    }
    setSaving(true)
    try {
      await api.post('/api/agent/favorites', {
        query: result.query,
        assetIds: visibleResults.map((item) => item.assetId),
      })
      toast.success('已保存到「保存的搜索」')
      await loadFavorites()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const deleteFavorite = async (favorite: SearchFavoriteItem) => {
    const confirmed = await confirm({
      title: '删除收藏',
      message: `删除「${favorite.query}」这组搜索结果？`,
      confirmText: '删除',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      await api.del(`/api/agent/favorites/${favorite.id}`)
      toast.success('已删除')
      await loadFavorites()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    }
  }

  const clearHistory = async () => {
    const confirmed = await confirm({
      title: '清空搜索历史',
      message: '清空全部搜索历史？收藏不受影响。',
      confirmText: '清空',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      await api.del('/api/agent/history')
      setHistory([])
      toast.success('搜索历史已清空')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '清空失败')
    }
  }

  return (
    <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_300px] lg:gap-6">
      <div className="min-w-0">
        <div
          ref={searchCardRef}
          onPaste={pasteImage}
          className={`card sticky top-4 z-30 p-5 transition duration-300 ${
            cardHidden ? '-translate-y-[130%] opacity-0 pointer-events-none' : 'translate-y-0 opacity-100'
          }`}
        >
          <form onSubmit={search} className="flex gap-3">
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="描述你想要的图片，或直接粘贴图片（可图片+文字组合搜索）"
              className="input flex-1"
            />
            <button type="submit" disabled={loading} className="btn-primary shrink-0">
              {loading ? '搜索中…' : '智能搜索'}
            </button>
          </form>
          {attachment && (
            <div className="mt-3 flex items-center gap-3 rounded-xl border border-stone-200/80 p-2">
              <img src={attachment.url} alt="粘贴的图片" className="h-12 w-12 rounded-lg object-cover" />
              <div className="min-w-0 flex-1">
                <div className="truncate text-[12px] font-medium text-stone-700">{attachment.file.name || '粘贴的图片'}</div>
                <div className="text-[11px] text-stone-400">
                  已附加 · 支持图片+文字 / 纯图片搜相似 / 读取图中文字
                </div>
              </div>
              <button
                type="button"
                onClick={removeAttachment}
                className="shrink-0 text-[11px] text-stone-400 transition hover:text-stone-700"
              >
                移除
              </button>
            </div>
          )}
          {history.length > 0 && (
            <div ref={historyRowRef} className="mt-3 flex items-center gap-2 overflow-x-auto pb-0.5">
              <span className="shrink-0 text-[11px] text-stone-400">历史</span>
              {history.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => void runSearch(item.query)}
                  title={`${formatDate(item.searchedAt)} 搜过`}
                  className="surface-solid shrink-0 rounded-full border border-stone-200 px-3 py-1 text-[12px] text-stone-600 transition hover:border-stone-300 hover:text-stone-900"
                >
                  {item.query}
                </button>
              ))}
              <button
                type="button"
                onClick={() => void clearHistory()}
                className="ml-auto shrink-0 text-[11px] text-stone-400 transition hover:text-stone-700"
              >
                清空
              </button>
            </div>
          )}
          <div className="mt-3 flex flex-wrap items-center gap-2.5 text-xs text-stone-500">
            <span className="shrink-0">置信度阈值</span>
            <input
              type="range"
              min={0}
              max={95}
              step={5}
              value={Math.round(threshold * 100)}
              onChange={(event) => updateThreshold(Number(event.target.value) / 100)}
              className="h-1.5 w-44 cursor-pointer accent-teal-700"
            />
            <span className="w-9 tabular-nums text-stone-600">{Math.round(threshold * 100)}%</span>
            {hiddenByThreshold > 0 && (
              <span className="text-stone-400">已隐藏 {hiddenByThreshold} 条低置信结果</span>
            )}
          </div>
          <p className="mt-3 text-xs text-stone-400">
            历史只记录查询词，点击会重新实时搜索；低于阈值的搜索结果不会显示，可拖动上方滑杆调整
          </p>
        </div>

        {loading && (
          <div className="card mt-4 p-4">
            <div className="flex items-center gap-2 text-sm font-medium text-stone-700">
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-stone-300 border-t-teal-700" />
              正在搜索「{searchStore.query || '图片'}」
            </div>
            {progressEvents.length > 0 && (
              <ul className="mt-3 space-y-1.5">
                {stageRows.map((event) => (
                  <li key={event.stage} className="flex items-center gap-2 text-xs text-stone-500">
                    {event.stage === activeStage ? (
                      <span className="h-3 w-3 shrink-0 animate-spin rounded-full border-2 border-stone-300 border-t-teal-700" />
                    ) : (
                      <svg
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.4"
                        className="h-3 w-3 shrink-0 text-teal-700"
                      >
                        <path strokeLinecap="round" strokeLinejoin="round" d="m5 12.5 4.5 4.5L19 7.5" />
                      </svg>
                    )}
                    <span className="chip shrink-0">{STAGE_LABELS[event.stage] ?? event.stage}</span>
                    <span>{event.message}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {progressEvents.length > 0 && (
          <SearchFlowPanel
            events={progressEvents}
            running={loading}
            graphAvailable={!!(graphVisual && graphVisual.nodes && graphVisual.nodes.length > 0)}
            graphOpen={graphOpen}
            onToggleGraph={toggleGraph}
          />
        )}
        {graphVisual && graphVisual.nodes && graphVisual.nodes.length > 0 && graphOpen && (
          <div ref={graphPanelRef}>
            <SimilarityGraphPanel data={graphVisual} onClose={() => setGraphOpen(false)} />
          </div>
        )}

        {!loading && searchStore.error && (
          <div className="card mt-4 border-dashed p-6 text-center text-sm text-stone-500">
            搜索失败：{searchStore.error}
            <button type="button" onClick={() => runSearch(searchStore.query)} className="btn-secondary btn-sm ml-3">
              重试
            </button>
          </div>
        )}

        {result && (
          <>
            <div className="mt-4 flex flex-wrap items-center gap-2 text-xs text-stone-500">
              <span className={result.aiEnabled ? 'chip-accent' : 'chip'}>
                AI {result.aiEnabled ? '已启用' : '未启用 · 关键字匹配'}
              </span>
              {result.keywords && result.keywords.length > 0 && (
                <span className="chip" title={result.keywords.join(' / ')}>
                  理解 {result.keywords.slice(0, 4).join(' / ')}
                </span>
              )}
              {result.colors && result.colors.length > 0 && (
                <span className="chip-accent">颜色 {result.colors.join(' / ')}</span>
              )}
              {result.excluded && result.excluded.length > 0 && (
                <span className="chip">排除 {result.excluded.join(' / ')}</span>
              )}
              {result.filters && result.filters.length > 0 && (
                <span className="chip-accent" title={result.filters.join(' / ')}>
                  筛选 {result.filters.join(' / ')}
                </span>
              )}
              {visibleResults.length > 0 && (
                <button
                  type="button"
                  onClick={() => void saveCurrent()}
                  disabled={saving}
                  className="btn-secondary btn-sm ml-auto"
                >
                  {saving ? '保存中…' : '保存本次结果'}
                </button>
              )}
            </div>
            {result.comment && (
              <div className="mt-2 flex items-start gap-2 text-xs text-stone-500">
                <span className="shrink-0 font-medium text-stone-600">Agent</span>
                <p className={`min-w-0 flex-1 leading-relaxed ${commentExpanded ? '' : 'line-clamp-1'}`}>
                  {result.comment}
                </p>
                <button
                  type="button"
                  onClick={() => setCommentExpanded((current) => !current)}
                  className="shrink-0 text-[11px] text-stone-400 transition hover:text-stone-700"
                >
                  {commentExpanded ? '收起' : '展开'}
                </button>
              </div>
            )}
          </>
        )}

        {result && result.results.length === 0 && !loading && (
          <div className="card mt-5 border-dashed py-20 text-center text-stone-400">没有找到相关图片</div>
        )}

        {result && result.results.length > 0 && visibleResults.length === 0 && !loading && (
          <div className="card mt-5 border-dashed py-16 text-center text-stone-400">
            全部 {result.results.length} 条结果都低于当前阈值 {Math.round(threshold * 100)}%，调低阈值即可显示
          </div>
        )}

        <div ref={resultsRef} className="mt-5 space-y-4">
          {visibleResults.map((item, index) => {
            const sunk = visibility.get(item.assetId) === false
            return (
            <div
              key={item.assetId}
              data-result-index={item.assetId}
              style={animatingIds.has(item.assetId) ? { animationDelay: `${Math.min(index, 8) * 25}ms` } : undefined}
              onAnimationEnd={() =>
                setAnimatingIds((current) => {
                  if (!current.has(item.assetId)) {
                    return current
                  }
                  const next = new Set(current)
                  next.delete(item.assetId)
                  return next
                })
              }
              className={`card float-card flex gap-4 p-3.5 hover:shadow-md${
                animatingIds.has(item.assetId) ? ' animate-rise-in' : ''
              }${sunk ? ' float-card-sunk' : ''}`}
            >
              <Link to={`/assets/${item.assetId}`} state={{ from: 'search' }} className="shrink-0">
                <AssetThumb
                  assetId={item.assetId}
                  alt={item.originalFilename}
                  className="h-28 w-36 rounded-xl bg-stone-100 object-cover"
                />
              </Link>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-3">
                  <Link
                    to={`/assets/${item.assetId}`}
                    state={{ from: 'search' }}
                    className="truncate font-medium text-stone-800 transition hover:text-teal-700"
                  >
                    {item.originalFilename}
                  </Link>
                  <span className="chip-accent shrink-0">置信度 {formatPercent(item.confidence)}</span>
                  {item.verified === false && (
                    <span
                      className="shrink-0 rounded-full bg-amber-100/80 px-2.5 py-0.5 text-xs font-medium text-amber-800"
                      title="该结果超过置信度阈值，但未经 AI 视觉二次确认"
                    >
                      未复核
                    </span>
                  )}
                  {item.characterName && <span className="chip shrink-0">{item.characterName}</span>}
                </div>
                <div className="mt-2 h-1.5 w-full max-w-xs overflow-hidden rounded-full bg-stone-100">
                  <div
                    className="progress-accent h-full rounded-full transition-all"
                    style={{ width: `${Math.round(item.confidence * 100)}%` }}
                  />
                </div>
                {item.description && <p className="mt-2 line-clamp-2 text-sm text-stone-500">{item.description}</p>}
                {item.tags.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-2">
                    {item.tags.slice(0, 6).map((tag) => (
                      <span key={tag} className="chip">
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
                <div className="mt-3 flex items-center gap-2">
                  <button type="button" onClick={() => void feedback(item.assetId, 'SELECT')} className="btn-primary btn-sm">
                    选它
                  </button>
                  <button
                    type="button"
                    onClick={() => void feedback(item.assetId, 'REJECT')}
                    className="btn-secondary btn-sm"
                  >
                    不相关
                  </button>
                  <span className="ml-auto self-center font-mono text-xs text-stone-300" title={item.sha256}>
                    {item.sha256.slice(0, 12)}…
                  </span>
                </div>
              </div>
            </div>
            )
          })}
        </div>
      </div>

      <aside className="mt-5 h-fit lg:mt-0 lg:sticky lg:top-4">
        <div className="card p-4">
          <h2 className="text-sm font-semibold text-stone-800">保存的搜索</h2>
          {favorites.length === 0 ? (
            <p className="mt-2 text-xs leading-relaxed text-stone-400">
              还没有收藏。搜索后点「保存本次结果」，这里可以随时回看。
            </p>
          ) : (
            <ul className="mt-3 space-y-3">
              {favorites.map((favorite) => (
                <li key={favorite.id} className="rounded-xl border border-stone-200/70 p-3">
                  <div className="flex items-start justify-between gap-2">
                    <button
                      type="button"
                      onClick={() => setExpanded((current) => (current === favorite.id ? null : favorite.id))}
                      className="min-w-0 flex-1 text-left"
                      title="展开/收起结果"
                    >
                      <div className="truncate text-[13px] font-medium text-stone-800">{favorite.query}</div>
                      <div className="mt-0.5 text-[11px] text-stone-400">
                        {formatDate(favorite.createdAt)} · {favorite.assetIds.length} 张
                        <span className="ml-1">{expanded === favorite.id ? '收起' : '展开'}</span>
                      </div>
                    </button>
                    <div className="flex shrink-0 gap-1">
                      <button
                        type="button"
                        title="再次搜索"
                        onClick={() => void runSearch(favorite.query)}
                        className="flex h-6 w-6 items-center justify-center rounded-full bg-stone-200/70 text-stone-500 transition hover:bg-stone-300 hover:text-stone-800"
                      >
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3.5 w-3.5">
                          <path strokeLinecap="round" d="M20 11a8 8 0 1 0-2.3 6.3M20 4v7h-7" />
                        </svg>
                      </button>
                      <button
                        type="button"
                        title="删除"
                        onClick={() => void deleteFavorite(favorite)}
                        className="flex h-6 w-6 items-center justify-center rounded-full bg-stone-200/70 text-stone-500 transition hover:bg-red-100 hover:text-red-600"
                      >
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3.5 w-3.5">
                          <path strokeLinecap="round" d="M6 6l12 12M18 6 6 18" />
                        </svg>
                      </button>
                    </div>
                  </div>
                  {expanded === favorite.id && (
                    <div className="mt-2 grid grid-cols-3 gap-1.5">
                      {favorite.assetIds.map((assetId) => (
                        <Link
                          key={assetId}
                          to={`/assets/${assetId}`}
                          state={{ from: 'search' }}
                          className="overflow-hidden rounded-lg border border-stone-200/60 transition hover:border-stone-300"
                        >
                          <AssetThumb
                            assetId={assetId}
                            alt={`资产 ${assetId}`}
                            className="aspect-[4/3] w-full bg-stone-100 object-cover"
                          />
                        </Link>
                      ))}
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>
    </div>
  )
}
