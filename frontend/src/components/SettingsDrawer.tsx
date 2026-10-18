import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useToast } from '../components/ToastProvider'

interface GraphSettings {
  enabled: boolean
  edgeThreshold: number
  maxEdgesPerAsset: number
  fetchPerChannel: number
  wd14Weight: number
  clipWeight: number
  singleChannelDiscount: number
  duplicateThreshold: number
  routeWeight: number
  routeSeedLimit: number
  routeNeighbors: number
  routeNearRank: number
  routeMidRank: number
  routeDecayNear: number
  routeDecayMid: number
  routeDecayFar: number
  feedbackOneHopFactor: number
  feedbackTwoHopFactor: number
  feedbackEdgeThreshold: number
  feedbackNeighborLimit: number
  feedbackSecondHopLimit: number
}

interface FieldDef {
  key: Exclude<keyof GraphSettings, 'enabled'>
  label: string
  step: number
  integer?: boolean
  hint?: string
}

const GROUPS: Array<{ title: string; fields: FieldDef[] }> = [
  {
    title: '建边（相似度图）',
    fields: [
      { key: 'edgeThreshold', label: '边阈值', step: 0.01, hint: '融合相似度达到该值才建立边' },
      { key: 'maxEdgesPerAsset', label: '每图最大边数', step: 1, integer: true },
      { key: 'fetchPerChannel', label: '每通道近邻数', step: 1, integer: true },
      { key: 'wd14Weight', label: 'WD14 权重', step: 0.05 },
      { key: 'clipWeight', label: 'CLIP 权重', step: 0.05 },
      { key: 'singleChannelDiscount', label: '单通道折减', step: 0.05 },
    ],
  },
  {
    title: '图路召回',
    fields: [
      { key: 'routeWeight', label: '图路权重', step: 0.1 },
      { key: 'routeSeedLimit', label: '种子数量', step: 1, integer: true },
      { key: 'routeNeighbors', label: '每种子邻居数', step: 1, integer: true },
      { key: 'routeNearRank', label: '近档名次', step: 1, integer: true },
      { key: 'routeMidRank', label: '中档名次', step: 1, integer: true },
      { key: 'routeDecayNear', label: '近档衰减', step: 0.05 },
      { key: 'routeDecayMid', label: '中档衰减', step: 0.05 },
      { key: 'routeDecayFar', label: '远档衰减', step: 0.05 },
    ],
  },
  {
    title: '反馈传播',
    fields: [
      { key: 'feedbackOneHopFactor', label: '1 跳系数', step: 0.05 },
      { key: 'feedbackTwoHopFactor', label: '2 跳系数', step: 0.05 },
      { key: 'feedbackEdgeThreshold', label: '传播边阈值', step: 0.05 },
      { key: 'feedbackNeighborLimit', label: '1 跳邻居上限', step: 1, integer: true },
      { key: 'feedbackSecondHopLimit', label: '2 跳邻居上限', step: 1, integer: true },
    ],
  },
  {
    title: '结果去重',
    fields: [{ key: 'duplicateThreshold', label: '近重复阈值', step: 0.01, hint: '同组结果只保留最高分' }],
  },
]

interface SettingsDrawerProps {
  open: boolean
  onClose: () => void
}

export function SettingsDrawer({ open, onClose }: SettingsDrawerProps) {
  const toast = useToast()
  const [effective, setEffective] = useState<GraphSettings | null>(null)
  const [defaults, setDefaults] = useState<GraphSettings | null>(null)
  const [draft, setDraft] = useState<GraphSettings | null>(null)
  const [saving, setSaving] = useState(false)
  const [rebuilding, setRebuilding] = useState(false)
  const [edges, setEdges] = useState<number | null>(null)

  const load = async () => {
    try {
      const response = await api.get<{ effective: GraphSettings; defaults: GraphSettings }>('/api/agent/graph/settings')
      setEffective(response.effective)
      setDefaults(response.defaults)
      setDraft(response.effective)
      const stats = await api.get<{ edges: number }>('/api/agent/graph/stats')
      setEdges(stats.edges)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '加载设置失败')
    }
  }

  useEffect(() => {
    if (open) {
      void load()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  useEffect(() => {
    if (!open) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const updateField = (key: Exclude<keyof GraphSettings, 'enabled'>, raw: string, integer: boolean) => {
    setDraft((current) => {
      if (!current) {
        return current
      }
      const parsed = integer ? parseInt(raw, 10) : Number(raw)
      if (!Number.isFinite(parsed)) {
        return current
      }
      return { ...current, [key]: parsed }
    })
  }

  const save = async () => {
    if (!draft) {
      return
    }
    setSaving(true)
    try {
      const response = await api.put<{ effective: GraphSettings; defaults: GraphSettings }>('/api/agent/graph/settings', draft)
      setEffective(response.effective)
      setDefaults(response.defaults)
      setDraft(response.effective)
      toast.success('设置已保存，立即生效')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const reset = async () => {
    setSaving(true)
    try {
      const response = await api.del<{ effective: GraphSettings; defaults: GraphSettings }>('/api/agent/graph/settings')
      setEffective(response.effective)
      setDefaults(response.defaults)
      setDraft(response.effective)
      toast.success('已恢复配置文件默认值')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '恢复默认失败')
    } finally {
      setSaving(false)
    }
  }

  const rebuild = async () => {
    setRebuilding(true)
    try {
      const count = await api.post<number>('/api/agent/graph/rebuild')
      toast.success(count > 0 ? `已重建 ${count} 张资产的相似边` : '重建被跳过（已在进行中或图已停用）')
      const stats = await api.get<{ edges: number }>('/api/agent/graph/stats')
      setEdges(stats.edges)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '重建失败')
    } finally {
      setRebuilding(false)
    }
  }

  const isDefault = (key: Exclude<keyof GraphSettings, 'enabled'>) =>
    effective != null && defaults != null && effective[key] === defaults[key]

  return (
    <aside
      aria-hidden={!open}
      className={`fixed right-0 top-0 z-40 h-full w-96 max-w-[92vw] transform transition-transform duration-300 ease-out ${
        open ? 'translate-x-0' : 'translate-x-full'
      }`}
    >
      <div className="surface-panel flex h-full flex-col border-l border-stone-200 shadow-2xl backdrop-blur-xl">
        <div className="flex items-center justify-between border-b border-stone-100 px-5 py-4">
          <div>
            <h2 className="text-sm font-semibold text-stone-800">检索调优 · 相似图</h2>
            <p className="mt-0.5 text-[11px] text-stone-400">保存后立即生效，无需重启</p>
          </div>
          <button type="button" onClick={onClose} className="btn-secondary btn-sm">
            关闭
          </button>
        </div>

        <div className="min-h-0 flex-1 space-y-5 overflow-y-auto px-5 py-4 text-sm">
          {!draft || !defaults ? (
            <p className="text-stone-400">加载中…</p>
          ) : (
            <>
              <label className="flex items-center justify-between rounded-xl border border-stone-200/80 px-3 py-2.5">
                <span className="text-[13px] text-stone-700">
                  启用相似图
                  <span className="mt-0.5 block text-[11px] text-stone-400">关闭后图路/去重/反馈传播全部停用</span>
                </span>
                <input
                  type="checkbox"
                  checked={draft.enabled}
                  onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })}
                  className="h-4 w-4 accent-teal-700"
                />
              </label>

              {GROUPS.map((group) => (
                <div key={group.title}>
                  <h3 className="text-[12px] font-medium text-stone-500">{group.title}</h3>
                  <div className="mt-2 space-y-2">
                    {group.fields.map((field) => (
                      <label key={field.key} className="flex items-center justify-between gap-3" title={field.hint}>
                        <span className={`text-[12px] ${isDefault(field.key) ? 'text-stone-500' : 'font-medium text-teal-700'}`}>
                          {field.label}
                          {!isDefault(field.key) && <span className="ml-1 text-[10px]">已调整</span>}
                        </span>
                        <input
                          type="number"
                          step={field.step}
                          value={draft[field.key]}
                          onChange={(event) => updateField(field.key, event.target.value, field.integer === true)}
                          className="input h-8 w-28 text-right"
                          title={`默认 ${defaults[field.key]}`}
                        />
                      </label>
                    ))}
                  </div>
                </div>
              ))}
            </>
          )}
        </div>

        <div className="border-t border-stone-100 px-5 py-4">
          <div className="flex items-center justify-between text-[11px] text-stone-400">
            <span>当前图边数：{edges ?? '—'}</span>
            <button type="button" onClick={() => void rebuild()} disabled={rebuilding} className="btn-secondary btn-sm">
              {rebuilding ? '重建中…' : '按当前参数重建图'}
            </button>
          </div>
          <div className="mt-3 flex gap-2">
            <button type="button" onClick={() => void save()} disabled={saving || !draft} className="btn-primary flex-1">
              {saving ? '保存中…' : '保存'}
            </button>
            <button type="button" onClick={() => void reset()} disabled={saving} className="btn-secondary">
              恢复默认
            </button>
          </div>
        </div>
      </div>
    </aside>
  )
}
