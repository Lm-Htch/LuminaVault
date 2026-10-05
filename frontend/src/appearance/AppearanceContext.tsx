import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

export type BackgroundType = 'theme' | 'solid' | 'image'
export type ThemeName = 'jade' | 'ink' | 'ocean' | 'violet' | 'rose' | 'amber'
export type ColorMode = 'light' | 'dark' | 'system'

export interface Appearance {
  theme: ThemeName
  colorMode: ColorMode
  backgroundType: BackgroundType
  solidColor: string
  imageUrl: string
  blur: number
  dim: number
  customCss: string
}

const STORAGE_KEY = 'lumina.appearance'

const DEFAULT_APPEARANCE: Appearance = {
  theme: 'jade',
  colorMode: 'light',
  backgroundType: 'theme',
  solidColor: '#f4f2ec',
  imageUrl: '',
  blur: 0,
  dim: 0.25,
  customCss: '',
}

function load(): Appearance {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      return { ...DEFAULT_APPEARANCE, ...(JSON.parse(raw) as Partial<Appearance>) }
    }
  } catch {
    // 忽略损坏的本地配置
  }
  return DEFAULT_APPEARANCE
}

interface AppearanceApi {
  appearance: Appearance
  update: (patch: Partial<Appearance>) => void
  reset: () => void
}

const AppearanceContext = createContext<AppearanceApi | null>(null)

export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [appearance, setAppearance] = useState<Appearance>(load)

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(appearance))
    const root = document.documentElement
    root.dataset.theme = appearance.theme

    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const applyColorMode = () => {
      const dark = appearance.colorMode === 'dark' || (appearance.colorMode === 'system' && media.matches)
      root.classList.toggle('dark', dark)
    }
    applyColorMode()

    root.style.setProperty('--app-bg-color', appearance.backgroundType === 'solid' ? appearance.solidColor : '')
    root.style.setProperty(
      '--app-bg-image',
      appearance.backgroundType === 'image' && appearance.imageUrl ? `url("${appearance.imageUrl}")` : 'none',
    )
    root.style.setProperty('--app-bg-blur', `${appearance.blur}px`)
    root.style.setProperty('--app-bg-dim', String(appearance.dim))

    let style = document.getElementById('lumina-custom-css') as HTMLStyleElement | null
    if (!style) {
      style = document.createElement('style')
      style.id = 'lumina-custom-css'
      document.head.appendChild(style)
    }
    style.textContent = appearance.customCss

    if (appearance.colorMode === 'system') {
      media.addEventListener('change', applyColorMode)
      return () => media.removeEventListener('change', applyColorMode)
    }
    return undefined
  }, [appearance])

  const update = useCallback((patch: Partial<Appearance>) => {
    setAppearance((current) => ({ ...current, ...patch }))
  }, [])

  const reset = useCallback(() => setAppearance(DEFAULT_APPEARANCE), [])

  const value = useMemo(() => ({ appearance, update, reset }), [appearance, update, reset])

  return <AppearanceContext.Provider value={value}>{children}</AppearanceContext.Provider>
}

export function useAppearance(): AppearanceApi {
  const context = useContext(AppearanceContext)
  if (!context) {
    throw new Error('useAppearance 必须在 AppearanceProvider 内使用')
  }
  return context
}
