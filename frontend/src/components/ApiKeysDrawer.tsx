import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'

interface ApiKey {
  id: number
  name: string
  keyPrefix: string
  scopes: string[]
  createdAt?: string
  expiresAt?: string | null
  lastUsedAt?: string | null
  revokedAt?: string | null
  active: boolean
}

interface CreatedKey {
  key: ApiKey
  secret: string
}

const SCOPE_OPTIONS: Array<{ value: string; label: string; hint: string }> = [
  { value: 'read', label: 'read · 只读', hint: '查询图库、搜索、读取图片' },
  { value: 'write', label: 'write · 读写', hint: '上传/修改/删除，含只读权限' },
]

function formatTime(value?: string | null) {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return date.toLocaleString()
}

interface ApiKeysDrawerProps {
  open: boolean
  onClose: () => void
  /** 打开 MCP 接入指引；传入刚创建的密钥则预填配置，传空串只打开指引。 */
  onConfigureMcp?: (secret: string) => void
}

export function ApiKeysDrawer({ open, onClose, onConfigureMcp }: ApiKeysDrawerProps) {
  const toast = useToast()
  const confirm = useConfirm()
  const [keys, setKeys] = useState<ApiKey[] | null>(null)
  const [name, setName] = useState('')
  const [scopes, setScopes] = useState<string[]>(['read', 'write'])
  const [expiresInDays, setExpiresInDays] = useState('')
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<CreatedKey | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  const load = async () => {
    try {
      const response = await api.get<ApiKey[]>('/api/keys')
      setKeys(response)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '加载密钥失败')
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

  const toggleScope = (value: string) => {
    setScopes((current) =>
      current.includes(value) ? current.filter((item) => item !== value) : [...current, value],
    )
  }

  const create = async () => {
    if (!name.trim()) {
      toast.error('请填写密钥名称')
      return
    }
    setCreating(true)
    try {
      const parsedExpiry = expiresInDays.trim() === '' ? null : parseInt(expiresInDays, 10)
      if (parsedExpiry != null && (!Number.isFinite(parsedExpiry) || parsedExpiry < 1)) {
        toast.error('有效期需为大于 0 的天数，留空表示长期有效')
        return
      }
      const response = await api.post<CreatedKey>('/api/keys', {
        name: name.trim(),
        scopes,
        expiresInDays: parsedExpiry,
      })
      setCreated(response)
      setName('')
      setExpiresInDays('')
      setScopes(['read', 'write'])
      await load()
      toast.success('密钥已创建，请立即复制保存')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '创建密钥失败')
    } finally {
      setCreating(false)
    }
  }

  const revoke = async (key: ApiKey) => {
    const confirmed = await confirm({
      title: '吊销该密钥？',
      message: `「${key.name}」吊销后立即失效，使用它的 MCP / 外部工具将无法再调用接口。`,
      confirmText: '吊销',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    setBusyId(key.id)
    try {
      await api.del<void>(`/api/keys/${key.id}`)
      if (created?.key.id === key.id) {
        setCreated(null)
      }
      await load()
      toast.success('密钥已吊销')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '吊销失败')
    } finally {
      setBusyId(null)
    }
  }

  const copySecret = async (secret: string) => {
    try {
      await navigator.clipboard.writeText(secret)
      toast.success('已复制到剪贴板')
    } catch {
      toast.error('复制失败，请手动选择文本复制')
    }
  }

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
            <h2 className="text-sm font-semibold text-stone-800">API 密钥</h2>
            <p className="mt-0.5 text-[11px] text-stone-400">供 MCP 与外部工具调用开放接口 /api/open/v1</p>
          </div>
          <button type="button" onClick={onClose} className="btn-secondary btn-sm">
            关闭
          </button>
        </div>

        <div className="min-h-0 flex-1 space-y-5 overflow-y-auto px-5 py-4 text-sm">
          {keys == null ? (
            <p className="text-stone-400">加载中…</p>
          ) : (
            <>
              {created && (
                <div className="rounded-xl border border-amber-300/70 bg-amber-50/70 px-3 py-3">
                  <p className="text-[12px] font-medium text-amber-900">密钥仅显示这一次</p>
                  <p className="mt-1 text-[11px] text-amber-800/80">
                    请立刻复制并保存到 MCP 配置中，关闭后无法再次查看。
                  </p>
                  <code className="mt-2 block break-all rounded-lg bg-white/80 px-2 py-1.5 text-[11px] text-stone-700">
                    {created.secret}
                  </code>
                  <div className="mt-2 flex gap-2">
                    <button
                      type="button"
                      onClick={() => void copySecret(created.secret)}
                      className="btn-primary btn-sm"
                    >
                      复制密钥
                    </button>
                    <button type="button" onClick={() => setCreated(null)} className="btn-secondary btn-sm">
                      我已保存
                    </button>
                    {onConfigureMcp && (
                      <button
                        type="button"
                        onClick={() => onConfigureMcp(created.secret)}
                        className="btn-secondary btn-sm"
                      >
                        去配置 MCP
                      </button>
                    )}
                  </div>
                </div>
              )}

              <div>
                <h3 className="text-[12px] font-medium text-stone-500">新建密钥</h3>
                <div className="mt-2 space-y-2">
                  <label className="block">
                    <span className="text-[12px] text-stone-600">名称</span>
                    <input
                      value={name}
                      onChange={(event) => setName(event.target.value)}
                      placeholder="例如 Claude Desktop"
                      maxLength={100}
                      className="input mt-1 w-full"
                    />
                  </label>

                  <div>
                    <span className="text-[12px] text-stone-600">权限</span>
                    <div className="mt-1 space-y-1">
                      {SCOPE_OPTIONS.map((option) => (
                        <label
                          key={option.value}
                          className="flex items-start justify-between gap-3 rounded-lg border border-stone-200/80 px-3 py-2"
                        >
                          <span className="text-[12px] text-stone-700">
                            {option.label}
                            <span className="mt-0.5 block text-[11px] text-stone-400">{option.hint}</span>
                          </span>
                          <input
                            type="checkbox"
                            checked={scopes.includes(option.value)}
                            onChange={() => toggleScope(option.value)}
                            className="mt-0.5 h-4 w-4 accent-teal-700"
                          />
                        </label>
                      ))}
                    </div>
                  </div>

                  <label className="block">
                    <span className="text-[12px] text-stone-600">有效期（天，留空为长期有效）</span>
                    <input
                      type="number"
                      min={1}
                      max={3650}
                      value={expiresInDays}
                      onChange={(event) => setExpiresInDays(event.target.value)}
                      placeholder="例如 365"
                      className="input mt-1 w-full"
                    />
                  </label>

                  <button
                    type="button"
                    onClick={() => void create()}
                    disabled={creating || scopes.length === 0}
                    className="btn-primary w-full"
                  >
                    {creating ? '创建中…' : '创建密钥'}
                  </button>
                  {scopes.length === 0 && <p className="text-[11px] text-amber-700">至少选择一种权限</p>}
                </div>
              </div>

              <div>
                <h3 className="text-[12px] font-medium text-stone-500">已有密钥（{keys.length}）</h3>
                {keys.length === 0 ? (
                  <p className="mt-2 text-[12px] text-stone-400">还没有密钥。</p>
                ) : (
                  <div className="mt-2 space-y-2">
                    {keys.map((key) => (
                      <div key={key.id} className="rounded-xl border border-stone-200/80 px-3 py-2.5">
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <p className="truncate text-[13px] font-medium text-stone-800">{key.name}</p>
                            <code className="mt-0.5 block truncate text-[11px] text-stone-500">
                              {key.keyPrefix}…
                            </code>
                          </div>
                          <button
                            type="button"
                            onClick={() => void revoke(key)}
                            disabled={!key.active || busyId === key.id}
                            className={key.active ? 'btn-secondary btn-sm' : 'btn-secondary btn-sm opacity-50'}
                          >
                            {busyId === key.id ? '处理中…' : key.active ? '吊销' : '已吊销'}
                          </button>
                        </div>
                        <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                          {key.scopes.map((scope) => (
                            <span
                              key={scope}
                              className="rounded-md bg-stone-200/70 px-1.5 py-0.5 text-[10px] text-stone-600"
                            >
                              {scope}
                            </span>
                          ))}
                          <span className="text-[10px] text-stone-400">创建 {formatTime(key.createdAt)}</span>
                        </div>
                        <div className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[10px] text-stone-400">
                          <span>最近使用 {formatTime(key.lastUsedAt)}</span>
                          <span>{key.expiresAt ? `到期 ${formatTime(key.expiresAt)}` : '长期有效'}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </>
          )}
        </div>

        <div className="border-t border-stone-100 px-5 py-4">
          <p className="text-[11px] leading-relaxed text-stone-400">
            MCP 配置使用环境变量 LUMINA_VAULT_URL 与 LUMINA_VAULT_API_KEY；密钥只在服务端保存 sha256
            摘要，泄露后请立即吊销重建。
          </p>
          <div className="mt-3 flex gap-2">
            {onConfigureMcp && (
              <button type="button" onClick={() => onConfigureMcp('')} className="btn-primary btn-sm flex-1">
                接入指引
              </button>
            )}
            <button type="button" onClick={() => void load()} className="btn-secondary btn-sm flex-1">
              刷新列表
            </button>
          </div>
        </div>
      </div>
    </aside>
  )
}
