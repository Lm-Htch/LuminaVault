import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import { useToast } from './ToastProvider'

/** `/api/mcp/config` 返回的本机部署信息（路径只有服务端知道，浏览器读不到）。 */
interface McpConfigInfo {
  projectRoot: string
  serverPath: string
  serverPathExists: boolean
  readmePath: string
  pythonCommand: string
  baseUrl: string
}

type ClientKind = 'claude' | 'cursor' | 'generic'

interface ClientPreset {
  value: ClientKind
  label: string
  configPath: string
  note: string
}

const CLIENT_PRESETS: ClientPreset[] = [
  {
    value: 'claude',
    label: 'Claude Desktop',
    configPath: 'Windows：%APPDATA%\\Claude\\claude_desktop_config.json ｜ macOS：~/Library/Application Support/Claude/claude_desktop_config.json',
    note: '改完配置需要完全退出（含托盘图标）再重新打开 Claude Desktop。',
  },
  {
    value: 'cursor',
    label: 'Cursor',
    configPath: '全局 ~/.cursor/mcp.json，或项目内 .cursor/mcp.json',
    note: '保存后在 Cursor 设置 → MCP 中确认 lumina-vault 为可用状态。',
  },
  {
    value: 'generic',
    label: '其他客户端',
    configPath: '任何支持 stdio 的 MCP 客户端：填入 command / args / env 三项即可',
    note: '传输方式是 stdio，一个客户端进程对应一个 MCP server 进程。',
  },
]

interface McpGuideDialogProps {
  open: boolean
  onClose: () => void
  /** 刚创建的密钥明文（来自 API 密钥抽屉），用于预填配置。 */
  secret?: string
  /** 还没有密钥时的回退动作（通常是打开 API 密钥抽屉）。 */
  onRequestKey?: () => void
}

/**
 * MCP 接入指引弹窗：把「创建密钥 → 填客户端配置 → 验证连通」讲清楚，并就地生成可复制配置。
 *
 * 密钥只存在于组件内用于拼接 JSON 与试连，不写存储、不回传服务端。
 */
export function McpGuideDialog({ open, onClose, secret, onRequestKey }: McpGuideDialogProps) {
  const toast = useToast()
  const [info, setInfo] = useState<McpConfigInfo | null>(null)
  const [key, setKey] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [client, setClient] = useState<ClientKind>('claude')
  const [reveal, setReveal] = useState(false)
  const [testing, setTesting] = useState(false)

  useEffect(() => {
    if (open) {
      setKey(secret ?? '')
      setReveal(false)
    }
  }, [open, secret])

  useEffect(() => {
    if (!open) {
      return
    }
    let cancelled = false
    api
      .get<McpConfigInfo>('/api/mcp/config')
      .then((response) => {
        if (cancelled) {
          return
        }
        setInfo(response)
        setBaseUrl(response.baseUrl)
      })
      .catch((err) => {
        if (!cancelled) {
          toast.error(err instanceof Error ? err.message : '读取 MCP 配置失败')
        }
      })
    return () => {
      cancelled = true
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

  const preset = CLIENT_PRESETS.find((item) => item.value === client) ?? CLIENT_PRESETS[0]

  const configJson = useMemo(() => {
    const config = {
      mcpServers: {
        'lumina-vault': {
          command: info?.pythonCommand ?? 'python',
          args: [info?.serverPath ?? '/绝对路径/mcp-server/lumina_vault_mcp.py'],
          env: {
            LUMINA_VAULT_URL: baseUrl || 'http://localhost:8080',
            LUMINA_VAULT_API_KEY: key.trim() || 'lv_替换为你的密钥',
          },
        },
      },
    }
    return JSON.stringify(config, null, 2)
  }, [info, baseUrl, key])

  const smokeCommand = useMemo(() => {
    const separator = info?.serverPath.includes('\\') ? '\\' : '/'
    const script = info ? `${info.serverPath.replace(/[\\/][^\\/]*$/, '')}${separator}smoke_test.py` : 'mcp-server/smoke_test.py'
    const secretText = key.trim() || 'lv_xxx'
    const python = info?.pythonCommand ?? 'python'
    return info?.pythonCommand === 'python'
      ? `$env:LUMINA_VAULT_API_KEY="${secretText}"; ${python} "${script}"`
      : `LUMINA_VAULT_API_KEY=${secretText} ${python} "${script}"`
  }, [info, key])

  const copy = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text)
      toast.success(`${label}已复制`)
    } catch {
      toast.error('复制失败，请手动选择文本复制')
    }
  }

  const testConnection = async () => {
    if (!key.trim()) {
      toast.error('请先填入 API 密钥')
      return
    }
    setTesting(true)
    try {
      const response = await fetch(`${baseUrl.replace(/\/+$/, '')}/api/open/v1/account`, {
        headers: { 'X-API-Key': key.trim() },
      })
      const payload = (await response.json().catch(() => null)) as
        | { code?: number; message?: string; data?: { username?: string; scopes?: string[] } }
        | null
      if (!response.ok || (payload?.code != null && payload.code !== 200)) {
        throw new Error(payload?.message || `HTTP ${response.status}`)
      }
      const scopes = payload?.data?.scopes?.join('/') || '未知'
      toast.success(`连接成功：${payload?.data?.username ?? '未知用户'} · 权限 ${scopes}`)
    } catch (err) {
      const message = err instanceof Error ? err.message : '连接失败'
      toast.error(message === 'Failed to fetch' ? '连接失败：后端未启动或地址不对' : `连接失败：${message}`)
    } finally {
      setTesting(false)
    }
  }

  if (!open) {
    return null
  }

  return (
    <div className="fixed inset-0 z-[75] flex items-center justify-center px-4 py-6">
      <div className="modal-backdrop animate-fade-in absolute inset-0 backdrop-blur-sm" onClick={onClose} />
      <div className="modal-panel scale-in relative flex max-h-[86vh] w-full max-w-2xl flex-col rounded-2xl border border-stone-200 shadow-2xl">
        <div className="flex items-start justify-between gap-4 border-b border-stone-100 px-6 py-4">
          <div>
            <h3 className="text-base font-semibold text-stone-900">接入 MCP 客户端</h3>
            <p className="mt-0.5 text-[11px] text-stone-400">
              让 Claude Desktop / Cursor 直接搜索、上传、管理这个图库
            </p>
          </div>
          <button type="button" onClick={onClose} className="btn-secondary btn-sm">
            关闭
          </button>
        </div>

        <div className="min-h-0 flex-1 space-y-5 overflow-y-auto px-6 py-5 text-sm">
          <section>
            <h4 className="text-[12px] font-medium text-stone-500">① 准备 API 密钥</h4>
            <div className="mt-2 flex items-center gap-2">
              <input
                type={reveal ? 'text' : 'password'}
                value={key}
                onChange={(event) => setKey(event.target.value)}
                placeholder="lv_…（粘贴你创建的密钥）"
                className="input min-w-0 flex-1 font-mono text-[12px]"
              />
              <button type="button" onClick={() => setReveal((current) => !current)} className="btn-secondary btn-sm">
                {reveal ? '隐藏' : '显示'}
              </button>
              {onRequestKey && (
                <button
                  type="button"
                  onClick={onRequestKey}
                  className="btn-secondary btn-sm whitespace-nowrap"
                >
                  还没有？
                </button>
              )}
            </div>
            <p className="mt-1.5 text-[11px] leading-relaxed text-stone-400">
              密钥只在本页面内用于拼接配置和试连，不会写入浏览器存储、也不会回传服务端；服务端只保存它的
              sha256 摘要。
            </p>
          </section>

          <section>
            <h4 className="text-[12px] font-medium text-stone-500">② 确认接入参数</h4>
            <div className="mt-2 space-y-2">
              <label className="block">
                <span className="text-[12px] text-stone-600">后端地址（LUMINA_VAULT_URL）</span>
                <input
                  value={baseUrl}
                  onChange={(event) => setBaseUrl(event.target.value)}
                  className="input mt-1 w-full font-mono text-[12px]"
                />
              </label>
              <div>
                <span className="text-[12px] text-stone-600">MCP server 脚本（服务端自动探测）</span>
                <div className="mt-1 flex items-center gap-2">
                  <code className="min-w-0 flex-1 truncate rounded-lg border border-stone-200 bg-stone-50 px-2 py-1.5 text-[11px] text-stone-600">
                    {info ? info.serverPath : '读取中…'}
                  </code>
                  <button
                    type="button"
                    onClick={() => info && void copy(info.serverPath, '脚本路径')}
                    disabled={!info}
                    className="btn-secondary btn-sm"
                  >
                    复制
                  </button>
                </div>
                {info && !info.serverPathExists && (
                  <p className="mt-1 text-[11px] text-amber-700">
                    未在该路径找到脚本：请确认部署时包含 <code>mcp-server/</code> 目录，或在客户端里改成实际路径。
                  </p>
                )}
              </div>
            </div>
          </section>

          <section>
            <h4 className="text-[12px] font-medium text-stone-500">③ 写入客户端配置</h4>
            <div className="mt-2 flex gap-1.5">
              {CLIENT_PRESETS.map((item) => (
                <button
                  key={item.value}
                  type="button"
                  onClick={() => setClient(item.value)}
                  className={`btn-sm rounded-lg px-3 py-1.5 text-[12px] transition ${
                    item.value === client
                      ? 'bg-stone-800 text-white'
                      : 'border border-stone-200 text-stone-600 hover:bg-stone-100'
                  }`}
                >
                  {item.label}
                </button>
              ))}
            </div>
            <p className="mt-2 text-[11px] leading-relaxed text-stone-400">{preset.configPath}</p>
            <pre className="mt-2 max-h-56 overflow-auto rounded-xl border border-stone-200 bg-stone-50 px-3 py-2 text-[11px] leading-relaxed text-stone-700">
              <code>{configJson}</code>
            </pre>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <button type="button" onClick={() => void copy(configJson, '配置')} className="btn-primary btn-sm">
                复制配置
              </button>
              <button type="button" onClick={() => void testConnection()} disabled={testing} className="btn-secondary btn-sm">
                {testing ? '试连中…' : '测试连接'}
              </button>
              <span className="text-[11px] text-stone-400">{preset.note}</span>
            </div>
          </section>

          <section>
            <h4 className="text-[12px] font-medium text-stone-500">④ 验证</h4>
            <p className="mt-2 text-[11px] leading-relaxed text-stone-400">
              在项目根目录跑一次冒烟测试：协议 6 项 + 只读 5 项 + 写入 9 项 + 错误处理 2 项，预期 22 项全通过。
            </p>
            <div className="mt-2 flex items-start gap-2">
              <code className="min-w-0 flex-1 break-all rounded-lg border border-stone-200 bg-stone-50 px-2 py-1.5 text-[11px] text-stone-600">
                {smokeCommand}
              </code>
              <button
                type="button"
                onClick={() => void copy(smokeCommand, '命令')}
                className="btn-secondary btn-sm"
              >
                复制
              </button>
            </div>
            {info && (
              <p className="mt-1.5 text-[11px] text-stone-400">
                完整工具清单与排错说明见 <code>{info.readmePath}</code>
              </p>
            )}
          </section>

          <section className="rounded-xl border border-stone-200/80 bg-stone-50/70 px-3 py-3">
            <h4 className="text-[12px] font-medium text-stone-600">常见问题</h4>
            <ul className="mt-1.5 space-y-1 text-[11px] leading-relaxed text-stone-500">
              <li>· 工具报 401：密钥填错或已被吊销——回「API 密钥」抽屉重新创建。</li>
              <li>· 工具报连接被拒：后端没在跑，或 LUMINA_VAULT_URL 写成了前端地址。</li>
              <li>· 让模型看图失败：单张超过 4MB 内联上限，改用 <code>thumbnail=true</code> 或取预签名 URL。</li>
              <li>· 只读用途：创建密钥时只勾 <code>read</code>，写入类工具会被服务端拒绝。</li>
            </ul>
          </section>
        </div>

        <div className="flex items-center justify-between gap-3 border-t border-stone-100 px-6 py-4">
          <p className="text-[11px] text-stone-400">配置改完记得重启客户端进程。</p>
          <button type="button" onClick={onClose} className="btn-primary btn-sm">
            完成
          </button>
        </div>
      </div>
    </div>
  )
}
