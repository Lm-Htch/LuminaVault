import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { AgentSearchResponse } from '../api/types'
import { AssetThumb } from '../components/AssetThumb'
import { useToast } from '../components/ToastProvider'
import { formatPercent } from '../utils/format'

export function AgentSearchPage() {
  const toast = useToast()
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<AgentSearchResponse | null>(null)
  const [loading, setLoading] = useState(false)

  const search = async (event?: FormEvent) => {
    event?.preventDefault()
    if (!query.trim()) {
      return
    }
    setLoading(true)
    try {
      setResult(await api.post<AgentSearchResponse>('/api/agent/search', { query: query.trim(), limit: 12 }))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '搜索失败')
    } finally {
      setLoading(false)
    }
  }

  const feedback = async (assetId: number, action: 'SELECT' | 'REJECT') => {
    try {
      await api.post('/api/agent/feedback', { assetId, action, query: query.trim() })
      toast.success(
        action === 'SELECT' ? '已记录你的选择，后续排序会偏向它' : '已记录，后续将降低相似推荐',
      )
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '反馈失败')
    }
  }

  return (
    <div>
        <div className="card sticky top-4 z-30 p-5">
        <form onSubmit={(event) => void search(event)} className="flex gap-3">
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="用自然语言描述你想要的图片，如：海边日落、黑白人像……"
            className="input flex-1"
          />
          <button type="submit" disabled={loading} className="btn-primary shrink-0">
            {loading ? '搜索中…' : '智能搜索'}
          </button>
        </form>
        <p className="mt-3 text-xs text-stone-400">
          由 AI 理解语义并二次确认；点击「选它」会让模型记住你的偏好，越用越准
        </p>
      </div>

      {result && (
        <div className="mt-4 flex flex-wrap items-center gap-3 text-xs text-stone-500">
          <span className={result.aiEnabled ? 'chip-accent' : 'chip'}>
            AI {result.aiEnabled ? '已启用' : '未启用 · 关键字匹配'}
          </span>
          {result.comment && <span className="text-stone-500">Agent：{result.comment}</span>}
        </div>
      )}

      {result && result.results.length === 0 && !loading && (
        <div className="card mt-5 border-dashed py-20 text-center text-stone-400">没有找到相关图片</div>
      )}

      <div className="mt-5 space-y-4">
        {result?.results.map((item) => (
          <div key={item.assetId} className="card animate-fade-in-up flex gap-4 p-3.5 transition hover:shadow-md">
            <Link to={`/assets/${item.assetId}`} className="shrink-0">
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
                  className="truncate font-medium text-stone-800 transition hover:text-teal-700"
                >
                  {item.originalFilename}
                </Link>
                <span className="chip-accent shrink-0">置信度 {formatPercent(item.confidence)}</span>
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
        ))}
      </div>
    </div>
  )
}
