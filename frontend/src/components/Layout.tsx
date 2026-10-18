import { useState } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { AppearanceDrawer } from '../appearance/AppearanceDrawer'
import { useAppearance } from '../appearance/AppearanceContext'
import { useAuth } from '../auth/AuthContext'
import { ApiKeysDrawer } from './ApiKeysDrawer'
import { McpGuideDialog } from './McpGuideDialog'
import { SettingsDrawer } from './SettingsDrawer'
import { UploadProvider } from './UploadProvider'

const links = [
  { to: '/', label: '图库', end: true },
  { to: '/folders', label: '文件夹', end: false },
  { to: '/agent', label: '智能搜索', end: false },
  { to: '/characters', label: '角色', end: false },
  { to: '/trash', label: '回收站', end: false },
]

function SunIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-4 w-4">
      <circle cx="12" cy="12" r="4" />
      <path
        strokeLinecap="round"
        d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
      />
    </svg>
  )
}

function MoonIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-4 w-4">
      <path strokeLinecap="round" strokeLinejoin="round" d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
    </svg>
  )
}

export function Layout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [appearanceOpen, setAppearanceOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [apiKeysOpen, setApiKeysOpen] = useState(false)
  const [mcpOpen, setMcpOpen] = useState(false)
  const [mcpSecret, setMcpSecret] = useState('')
  const { appearance, update } = useAppearance()

  const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  const effectiveDark = appearance.colorMode === 'dark' || (appearance.colorMode === 'system' && systemDark)

  return (
    <UploadProvider>
      <div className="flex h-dvh flex-col">
      <header className="topbar-surface z-10 border-b border-stone-200/70 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-screen-2xl items-center gap-8 px-6">
          <Link to="/" className="flex items-center gap-2.5">
            <span className="brand-accent flex h-9 w-9 items-center justify-center rounded-xl text-lg font-semibold text-white">
              琅
            </span>
            <span className="hidden text-base font-semibold tracking-wide text-stone-900 sm:block">
              LuminaVault
            </span>
          </Link>
          <nav className="flex items-center gap-1 text-sm">
            {links.map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                className={({ isActive }) =>
                  `rounded-lg px-3 py-2 transition ${
                    isActive
                      ? 'bg-stone-200/70 font-medium text-stone-900'
                      : 'text-stone-500 hover:bg-stone-200/50 hover:text-stone-900'
                  }`
                }
              >
                {link.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3">
            <span className="hidden max-w-40 truncate text-sm text-stone-500 sm:block">
              {user?.nickname || user?.username}
            </span>
            <button
              type="button"
              onClick={() => update({ colorMode: effectiveDark ? 'light' : 'dark' })}
              className="btn-secondary h-8 w-8 p-0"
              title={effectiveDark ? '切换为亮色' : '切换为暗色'}
            >
              {effectiveDark ? <SunIcon /> : <MoonIcon />}
            </button>
            <button type="button" onClick={() => setAppearanceOpen(true)} className="btn-secondary btn-sm">
              外观
            </button>
            <button type="button" onClick={() => setSettingsOpen(true)} className="btn-secondary btn-sm">
              设置
            </button>
            <button type="button" onClick={() => setApiKeysOpen(true)} className="btn-secondary btn-sm">
              API 密钥
            </button>
            <button type="button" onClick={() => setMcpOpen(true)} className="btn-secondary btn-sm">
              MCP 接入
            </button>
            <button
              type="button"
              onClick={async () => {
                await logout()
                navigate('/login', { replace: true })
              }}
              className="btn-secondary btn-sm"
            >
              退出
            </button>
          </div>
        </div>
      </header>
      <main className="main-scroll min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto h-full max-w-screen-2xl px-6 py-8">
          <Outlet />
        </div>
      </main>
      <AppearanceDrawer open={appearanceOpen} onClose={() => setAppearanceOpen(false)} />
      <SettingsDrawer open={settingsOpen} onClose={() => setSettingsOpen(false)} />
      <ApiKeysDrawer
        open={apiKeysOpen}
        onClose={() => setApiKeysOpen(false)}
        onConfigureMcp={(secret) => {
          setMcpSecret(secret)
          setApiKeysOpen(false)
          setMcpOpen(true)
        }}
      />
      <McpGuideDialog
        open={mcpOpen}
        secret={mcpSecret}
        onClose={() => setMcpOpen(false)}
        onRequestKey={() => {
          setMcpOpen(false)
          setApiKeysOpen(true)
        }}
      />
      </div>
    </UploadProvider>
  )
}
