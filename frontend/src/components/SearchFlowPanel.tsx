export interface SearchFlowEvent {
  stage: string
  message: string
  current?: number | null
  total?: number | null
  detail?: Record<string, unknown> | null
}

interface SearchFlowPanelProps {
  events: SearchFlowEvent[]
  running: boolean
  graphAvailable?: boolean
  graphOpen?: boolean
  onToggleGraph?: () => void
}

const STAGES: Array<{ stage: string; label: string; hint?: string }> = [
  { stage: 'analyze', label: '意图解析', hint: '文本/图片理解与查询意图拆解' },
  { stage: 'retrieve', label: '多路召回', hint: '向量 / 标签 / 模糊 / 颜色 / 图像等各路候选' },
  { stage: 'graph', label: '无向图扩展', hint: '以召回头部为种子，沿相似图查找邻居（图面板可见查找过程）' },
  { stage: 'rerank', label: '精排', hint: 'cross-encoder / LLM 重排' },
  { stage: 'adjust', label: '规则校正', hint: '发色等矛盾规则降权' },
  { stage: 'vision', label: '视觉确认', hint: '超过阈值的候选交给 LLM 看图复核' },
  { stage: 'dedupe', label: '去重', hint: '近重复（强边）同组只保留最高分' },
  { stage: 'done', label: '结果', hint: '阈值过滤后的最终结果' },
]

function detailNumber(event: SearchFlowEvent, key: string): number | null {
  const raw = event.detail?.[key]
  return typeof raw === 'number' ? raw : null
}

function summarize(stage: string, event: SearchFlowEvent): string {
  switch (stage) {
    case 'cache':
      return '命中'
    case 'analyze': {
      const message = event.message.replace(/^意图：/, '')
      return message.length > 10 ? `${message.slice(0, 10)}…` : message
    }
    case 'retrieve': {
      const candidates = detailNumber(event, 'candidates')
      const routes = event.detail?.routes
      const routeCount = routes && typeof routes === 'object' ? Object.keys(routes as object).length : null
      return `${candidates ?? '—'} 张 / ${routeCount ?? '—'} 路`
    }
    case 'graph': {
      const seeds = detailNumber(event, 'seeds')
      const neighbors = detailNumber(event, 'neighbors')
      return `种子 ${seeds ?? '—'} → ${neighbors ?? '—'}`
    }
    case 'rerank':
      return event.message.includes('完成') || event.message.includes('张') ? '完成' : event.message.slice(0, 8)
    case 'adjust':
      return `-${detailNumber(event, 'adjusted') ?? '?'} 张`
    case 'vision': {
      if (event.message.includes('完成')) {
        return '完成'
      }
      return event.message.replace('视觉确认 ', '') || '…'
    }
    case 'dedupe':
      return `-${detailNumber(event, 'suppressed') ?? '?'} 张`
    case 'done':
      return `${detailNumber(event, 'results') ?? '?'} 条`
    default:
      return ''
  }
}

/**
 * 「搜索过程」节点流程板块：固定节点顺序展示完整管线，已发生的阶段带真实数据。
 * 样式克制：进行中为小型转圈，完成为对勾，未触发为虚线置灰；不做高亮闪烁
 * （无向图查找的闪烁动画在「相似图」面板中呈现）。
 */
export function SearchFlowPanel({ events, running, graphAvailable, graphOpen, onToggleGraph }: SearchFlowPanelProps) {
  const byStage = new Map<string, SearchFlowEvent>()
  for (const event of events) {
    byStage.set(event.stage, event)
  }
  const stages = byStage.has('cache') ? [{ stage: 'cache', label: '缓存命中' }, ...STAGES.slice(1)] : STAGES
  const activeStage = running && events.length > 0 ? events[events.length - 1].stage : null

  return (
    <div className="card mt-4 p-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-medium text-stone-700">搜索过程</h2>
        <span className="text-[11px] text-stone-400">{running ? '节点流程 · 实时' : '节点流程 · 上次搜索'}</span>
      </div>
      <div className="mt-3 flex items-center gap-1 overflow-x-auto pb-1">
        {stages.map((def, index) => {
          const event = byStage.get(def.stage)
          const active = def.stage === activeStage
          const done = event != null
          const clickable = def.stage === 'graph' && graphAvailable && onToggleGraph != null
          const body = (
            <>
              <span className="flex items-center gap-1 text-[10px] text-stone-400">
                {active && <span className="h-2.5 w-2.5 animate-spin rounded-full border border-stone-300 border-t-teal-700" />}
                {done && !active && (
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" className="h-2.5 w-2.5 text-teal-600">
                    <path strokeLinecap="round" strokeLinejoin="round" d="m5 12.5 4.5 4.5L19 7.5" />
                  </svg>
                )}
                {def.label}
                {clickable && (
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.4"
                    className={`h-2.5 w-2.5 transition-transform duration-200 ${graphOpen ? '' : 'rotate-180'}`}
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" d="m6 15 6-6 6 6" />
                  </svg>
                )}
              </span>
              <span className="mt-0.5 text-[12px] font-medium leading-tight">
                {event ? summarize(def.stage, event) : running ? '…' : '—'}
              </span>
            </>
          )
          const className = `flex min-w-16 flex-col items-center rounded-lg border px-2.5 py-1.5 text-center transition ${
            active
              ? 'border-teal-500/50 bg-teal-500/10 text-stone-800'
              : done
                ? 'border-stone-200/80 text-stone-600'
                : 'border-dashed border-stone-200 text-stone-300'
          }${clickable ? ' cursor-pointer hover:border-teal-400' : ''}`
          const title = clickable
            ? `${def.hint ?? ''}\n点击${graphOpen ? '收起' : '打开'}相似图面板\n${event?.message ?? ''}`
            : def.hint
              ? `${def.hint}\n${event?.message ?? ''}`
              : event?.message
          return (
            <div key={def.stage} className="flex shrink-0 items-center gap-1">
              {index > 0 && <span className="h-px w-4 shrink-0 bg-stone-200" />}
              {clickable ? (
                <button type="button" onClick={onToggleGraph} className={className} title={title}>
                  {body}
                </button>
              ) : (
                <div className={className} title={title}>
                  {body}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
