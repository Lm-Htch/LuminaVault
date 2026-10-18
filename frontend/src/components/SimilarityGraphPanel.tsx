import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { fetchAssetDetail } from '../api/assetDetailCache'

export interface GraphVisualizationData {
  seeds?: number
  neighbors?: number
  nodes?: Array<{ id: number; seed?: boolean; label?: string }>
  edges?: Array<{ a: number; b: number; score?: number }>
}

interface SimilarityGraphPanelProps {
  data: GraphVisualizationData
  onClose?: () => void
}

const WIDTH = 900
const PADDING_Y = 42
const ROW = 48
const SEED_X = 150
const NEIGHBOR_X = 750
const SEED_SIZE = 44
const NEIGHBOR_SIZE = 30
const MAX_SEEDS_SHOWN = 10
const MAX_NEIGHBORS_SHOWN = 14

interface PlacedNode {
  id: number
  x: number
  y: number
  label: string
  delay: number
}

interface PlacedEdge {
  key: string
  x1: number
  y1: number
  x2: number
  y2: number
  score: number
  delay: number
}

interface GraphModel {
  seedNodes: PlacedNode[]
  neighborNodes: PlacedNode[]
  edges: PlacedEdge[]
  height: number
  seedCount: number
  neighborCount: number
  shownNeighbors: number
}

function buildModel(data: GraphVisualizationData): GraphModel {
  const nodes = data.nodes ?? []
  const edges = data.edges ?? []
  const seedSource = nodes.filter((node) => node.seed)
  const neighborSource = nodes.filter((node) => !node.seed)
  if (seedSource.length === 0 || edges.length === 0) {
    return {
      seedNodes: [], neighborNodes: [], edges: [], height: 220,
      seedCount: seedSource.length, neighborCount: neighborSource.length, shownNeighbors: 0,
    }
  }
  const bestEdge = new Map<number, { a: number; b: number; score: number }>()
  for (const edge of edges) {
    const score = typeof edge.score === 'number' ? edge.score : 0
    const existing = bestEdge.get(edge.b)
    if (!existing || score > existing.score) {
      bestEdge.set(edge.b, { a: edge.a, b: edge.b, score })
    }
  }
  const ranked = neighborSource
    .map((node) => ({ node, edge: bestEdge.get(node.id) }))
    .filter((entry) => entry.edge != null)
    .sort((left, right) => (right.edge!.score ?? 0) - (left.edge!.score ?? 0))
    .slice(0, MAX_NEIGHBORS_SHOWN)

  const seedOrder = new Map<number, number>()
  seedSource.forEach((node, index) => seedOrder.set(node.id, index))

  const childrenBySeed = new Map<number, typeof ranked>()
  for (const entry of ranked) {
    const seedId = entry.edge!.a
    if (!childrenBySeed.has(seedId)) {
      childrenBySeed.set(seedId, [])
    }
    childrenBySeed.get(seedId)!.push(entry)
  }
  const orderedSeedIds = [...childrenBySeed.keys()]
    .sort((left, right) => (seedOrder.get(left) ?? 0) - (seedOrder.get(right) ?? 0))
    .slice(0, MAX_SEEDS_SHOWN)

  const neighborNodes: PlacedNode[] = []
  const seedY = new Map<number, number>()
  let slot = 0
  for (const seedId of orderedSeedIds) {
    const children = childrenBySeed.get(seedId) ?? []
    const ys: number[] = []
    for (const entry of children) {
      const y = PADDING_Y + slot * ROW + ROW / 2
      ys.push(y)
      neighborNodes.push({
        id: entry.node.id,
        x: NEIGHBOR_X,
        y,
        label: entry.node.label ?? `#${entry.node.id}`,
        delay: 0,
      })
      slot++
    }
    if (ys.length > 0) {
      seedY.set(seedId, ys.reduce((sum, value) => sum + value, 0) / ys.length)
    }
  }

  const seedNodes: PlacedNode[] = []
  const edgesPlaced: PlacedEdge[] = []
  const edgeStart = orderedSeedIds.length * 40
  const neighborStart = edgeStart + neighborNodes.length * 22
  let edgeCursor = 0
  orderedSeedIds.forEach((seedId, index) => {
    const y = seedY.get(seedId)
    if (y == null) {
      return
    }
    const seedNode = seedSource.find((node) => node.id === seedId)
    seedNodes.push({
      id: seedId,
      x: SEED_X,
      y,
      label: seedNode?.label ?? `#${seedId}`,
      delay: index * 40,
    })
    for (const entry of childrenBySeed.get(seedId) ?? []) {
      const neighbor = neighborNodes.find((node) => node.id === entry.node.id)
      if (!neighbor) {
        continue
      }
      edgesPlaced.push({
        key: `${seedId}-${entry.node.id}`,
        x1: SEED_X,
        y1: y,
        x2: NEIGHBOR_X,
        y2: neighbor.y,
        score: entry.edge!.score,
        delay: edgeStart + edgeCursor * 22,
      })
      neighbor.delay = neighborStart + edgeCursor * 40
      edgeCursor++
    }
  })

  const totalRows = Math.max(slot, seedNodes.length)
  const height = Math.max(220, totalRows * ROW + PADDING_Y * 2)
  return {
    seedNodes,
    neighborNodes,
    edges: edgesPlaced,
    height,
    seedCount: seedNodes.length,
    neighborCount: neighborSource.length,
    shownNeighbors: neighborNodes.length,
  }
}

function GraphThumb({ assetId }: { assetId: number }) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    fetchAssetDetail(assetId)
      .then((detail) => {
        if (alive) {
          setUrl(detail.thumbnailUrl ?? detail.url)
        }
      })
      .catch(() => undefined)
    return () => {
      alive = false
    }
  }, [assetId])
  if (!url) {
    return <span className="block h-full w-full bg-stone-300/40" />
  }
  return <img src={url} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" />
}

/**
 * 「相似图 · 无向图查找」面板：缩略图为节点（点击灯箱放大），发光贝塞尔边为查找经过的路径。
 * 支持「全屏」详细模式；由搜索过程面板的「无向图扩展」节点控制开合。
 */
export function SimilarityGraphPanel({ data, onClose }: SimilarityGraphPanelProps) {
  const [replay, setReplay] = useState(0)
  const [zoom, setZoom] = useState<{ url: string; label: string } | null>(null)
  const [expanded, setExpanded] = useState(false)
  const model = useMemo(() => buildModel(data), [data])

  useEffect(() => {
    if (!zoom && !expanded) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') {
        return
      }
      if (zoom) {
        setZoom(null)
      } else if (expanded) {
        setExpanded(false)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [zoom, expanded])

  const openZoom = async (id: number, label: string) => {
    try {
      const detail = await fetchAssetDetail(id)
      setZoom({ url: detail.url ?? detail.thumbnailUrl, label })
    } catch {
      // 拉取失败时忽略
    }
  }

  const nodeButton = (node: PlacedNode, seed: boolean) => (
    <button
      key={`${seed ? 's' : 'n'}-${node.id}`}
      type="button"
      onClick={() => void openZoom(node.id, node.label)}
      title={`${node.label}${seed ? '（种子）' : '（邻居）'} · 点击放大`}
      className={`absolute -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-lg ring-teal-500/70 transition duration-200 hover:z-30 hover:scale-125 hover:ring-2 ${
        seed ? 'z-10 ring-2 ring-teal-600/80 shadow-md shadow-teal-900/20' : 'z-10 ring-1 ring-teal-600/40'
      } ${seed ? 'graph-node-in' : 'graph-node-pop'}`}
      style={{
        left: `${(node.x / WIDTH) * 100}%`,
        top: `${(node.y / model.height) * 100}%`,
        width: seed ? SEED_SIZE : NEIGHBOR_SIZE,
        height: seed ? SEED_SIZE : NEIGHBOR_SIZE,
        animationDelay: seed ? `${node.delay}ms` : `${node.delay}ms, ${node.delay + 320}ms`,
      }}
    >
      <GraphThumb assetId={node.id} />
    </button>
  )

  const canvas = (canvasKey: string) => (
    <div key={`${canvasKey}-${replay}`} className="relative mt-3 w-full" style={{ aspectRatio: `${WIDTH} / ${model.height}` }}>
      <svg className="absolute inset-0 h-full w-full" viewBox={`0 0 ${WIDTH} ${model.height}`} aria-hidden="true">
        <defs>
          <linearGradient id={`graph-edge-gradient-${canvasKey}`} x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#0d9488" stopOpacity="0.85" />
            <stop offset="100%" stopColor="#14b8a6" stopOpacity="0.35" />
          </linearGradient>
        </defs>
        {model.edges.map((edge) => {
          const d = `M ${edge.x1} ${edge.y1} C ${edge.x1 + 160} ${edge.y1}, ${edge.x2 - 160} ${edge.y2}, ${edge.x2} ${edge.y2}`
          const weight = Math.max(0, Math.min(1, edge.score))
          return (
            <g key={edge.key}>
              <path
                d={d}
                pathLength={1}
                fill="none"
                stroke={`url(#graph-edge-gradient-${canvasKey})`}
                strokeOpacity={0.25 + 0.55 * weight}
                strokeWidth={0.9 + 1.6 * weight}
                className="graph-edge-draw"
                style={{ animationDelay: `${edge.delay}ms` }}
              />
              <path
                d={d}
                fill="none"
                stroke="#2dd4bf"
                strokeWidth={1.2}
                strokeLinecap="round"
                className="graph-edge-flow"
                style={{ animationDelay: `${edge.delay + 450}ms` }}
              />
            </g>
          )
        })}
      </svg>
      {model.seedNodes.map((node) => nodeButton(node, true))}
      {model.neighborNodes.map((node) => nodeButton(node, false))}
    </div>
  )

  const header = (inModal: boolean) =>
    (
      <div className="flex items-start justify-between gap-2">
        <div>
          <h2 className="text-sm font-medium text-stone-700">相似图 · 无向图查找</h2>
          <p className="mt-0.5 text-[11px] text-stone-400">
            缩略图 = 资产（点击放大） · 左 = 搜索头部种子（{model.seedCount}） · 右 = 图扩展邻居（高分前 {model.shownNeighbors}
            {model.neighborCount > model.shownNeighbors ? ` / 共 ${model.neighborCount}` : ''}） · 闪烁 = 查找经过
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <button type="button" onClick={() => setReplay((value) => value + 1)} className="btn-secondary btn-sm">
            重播
          </button>
          <button type="button" onClick={() => setExpanded(!inModal)} className="btn-secondary btn-sm">
            {inModal ? '还原' : '全屏'}
          </button>
          {!inModal && onClose && (
            <button type="button" onClick={onClose} className="btn-secondary btn-sm">
              收起
            </button>
          )}
        </div>
      </div>
    )

  const content = (emptyHint: string) =>
    model.seedNodes.length === 0 ? <p className="mt-3 text-stone-400">{emptyHint}</p> : canvas('c')

  return (
    <>
      <div className="card mt-4 p-4">
        {header(false)}
        {content('本次搜索没有经过无向图扩展（未建图或种子无邻居）。')}
      </div>

      {expanded &&
        createPortal(
          <div
            className="fixed inset-0 z-[110] flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm sm:p-8"
            onClick={() => setExpanded(false)}
          >
            <div
              className="card animate-fade-in max-h-[94vh] w-full max-w-[1380px] overflow-auto p-5"
              onClick={(event) => event.stopPropagation()}
            >
              {header(true)}
              {content('本次搜索没有经过无向图扩展。')}
            </div>
          </div>,
          document.body,
        )}

      {zoom &&
        createPortal(
          <div
            className="fixed inset-0 z-[120] flex items-center justify-center bg-black/80 p-6 backdrop-blur-sm animate-fade-in"
            onClick={() => setZoom(null)}
          >
            <button
              type="button"
              onClick={() => setZoom(null)}
              className="absolute right-5 top-5 flex h-9 w-9 items-center justify-center rounded-full bg-white/10 text-white/80 transition hover:bg-white/20 hover:text-white"
              title="关闭（Esc）"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-4 w-4">
                <path strokeLinecap="round" d="M6 6l12 12M18 6 6 18" />
              </svg>
            </button>
            <figure className="flex max-h-full max-w-full flex-col items-center" onClick={(event) => event.stopPropagation()}>
              <img
                src={zoom.url}
                alt={zoom.label}
                className="max-h-[82vh] max-w-[88vw] rounded-xl object-contain shadow-2xl"
              />
              <figcaption className="mt-3 rounded-full bg-white/10 px-3 py-1 text-xs text-white/85">
                {zoom.label} · 点击背景或按 Esc 关闭
              </figcaption>
            </figure>
          </div>,
          document.body,
        )}
    </>
  )
}
