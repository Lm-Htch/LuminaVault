import { useEffect, useRef, useState } from 'react'
import { useAppearance, type BackgroundType, type ColorMode, type ThemeName } from './AppearanceContext'
import { useToast } from '../components/ToastProvider'

const COLOR_MODES: Array<{ value: ColorMode; label: string }> = [
  { value: 'light', label: '亮色' },
  { value: 'dark', label: '暗色' },
  { value: 'system', label: '跟随系统' },
]

const THEMES: Array<{ name: ThemeName; label: string; color: string }> = [
  { name: 'jade', label: '青瓷', color: '#0f766e' },
  { name: 'ink', label: '墨', color: '#1c1917' },
  { name: 'ocean', label: '海蓝', color: '#1d4ed8' },
  { name: 'violet', label: '紫罗兰', color: '#7c3aed' },
  { name: 'rose', label: '绯红', color: '#e11d48' },
  { name: 'amber', label: '琥珀', color: '#b45309' },
]

const BACKGROUNDS: Array<{ value: BackgroundType; label: string }> = [
  { value: 'theme', label: '主题' },
  { value: 'solid', label: '纯色' },
  { value: 'image', label: '图片' },
]

interface AppearanceDrawerProps {
  open: boolean
  onClose: () => void
}

export function AppearanceDrawer({ open, onClose }: AppearanceDrawerProps) {
  const { appearance, update, reset } = useAppearance()
  const toast = useToast()
  const [cssDraft, setCssDraft] = useState(appearance.customCss)
  const cssFile = useRef<HTMLInputElement>(null)
  const imageFile = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open) {
      setCssDraft(appearance.customCss)
    }
  }, [open, appearance.customCss])

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

  const applyCss = () => {
    update({ customCss: cssDraft })
    toast.success(cssDraft.trim() ? '自定义 CSS 已应用' : '自定义 CSS 已清空')
  }

  const importCssFile = (file: File | null) => {
    if (!file) {
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const text = String(reader.result ?? '')
      setCssDraft(text)
      update({ customCss: text })
      toast.success(`已导入 ${file.name}`)
    }
    reader.readAsText(file)
  }

  const importImage = (file: File | null) => {
    if (!file) {
      return
    }
    if (file.size > 6 * 1024 * 1024) {
      toast.error('图片过大（建议 6MB 以内）')
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      update({ backgroundType: 'image', imageUrl: String(reader.result ?? '') })
      toast.success('背景图片已更新')
    }
    reader.readAsDataURL(file)
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
          <h2 className="text-sm font-semibold text-stone-800">外观设置</h2>
          <button
            type="button"
            onClick={onClose}
            className="flex h-7 w-7 items-center justify-center rounded-lg text-stone-400 transition hover:bg-stone-100 hover:text-stone-700"
            aria-label="关闭"
          >
            ✕
          </button>
        </div>

        <div className="flex-1 space-y-6 overflow-auto px-5 py-5">
          <section>
            <h3 className="text-xs font-medium uppercase tracking-wide text-stone-400">明暗模式</h3>
            <div className="mt-3 grid grid-cols-3 gap-2 rounded-xl bg-stone-100 p-1">
              {COLOR_MODES.map((mode) => (
                <button
                  key={mode.value}
                  type="button"
                  onClick={() => update({ colorMode: mode.value })}
                  className={`rounded-lg px-3 py-1.5 text-xs font-medium transition ${
                    appearance.colorMode === mode.value
                      ? 'surface-solid text-stone-900 shadow-sm'
                      : 'text-stone-500 hover:text-stone-800'
                  }`}
                >
                  {mode.label}
                </button>
              ))}
            </div>
            <p className="mt-2 text-xs text-stone-400">仅调整卡片与文字的明暗，与主题色、背景相互独立</p>
          </section>

          <section>
            <h3 className="text-xs font-medium uppercase tracking-wide text-stone-400">主题色</h3>
            <div className="mt-3 flex flex-wrap gap-3">
              {THEMES.map((theme) => (
                <button
                  key={theme.name}
                  type="button"
                  title={theme.label}
                  onClick={() => update({ theme: theme.name })}
                  className={`accent-swatch ${appearance.theme === theme.name ? 'ring-2 ring-stone-900 ring-offset-2' : ''}`}
                  style={{ backgroundColor: theme.color }}
                />
              ))}
            </div>
          </section>

          <section>
            <h3 className="text-xs font-medium uppercase tracking-wide text-stone-400">背景</h3>
            <div className="mt-3 grid grid-cols-3 gap-2 rounded-xl bg-stone-100 p-1">
              {BACKGROUNDS.map((item) => (
                <button
                  key={item.value}
                  type="button"
                  onClick={() => update({ backgroundType: item.value })}
                  className={`rounded-lg px-3 py-1.5 text-xs font-medium transition ${
                    appearance.backgroundType === item.value
                      ? 'surface-solid text-stone-900 shadow-sm'
                      : 'text-stone-500 hover:text-stone-800'
                  }`}
                >
                  {item.label}
                </button>
              ))}
            </div>

            {appearance.backgroundType === 'solid' && (
              <div className="mt-4 flex items-center gap-3">
                <input
                  type="color"
                  value={appearance.solidColor}
                  onChange={(event) => update({ solidColor: event.target.value })}
                  className="surface-solid h-9 w-14 cursor-pointer rounded border border-stone-300"
                />
                <input
                  value={appearance.solidColor}
                  onChange={(event) => update({ solidColor: event.target.value })}
                  className="input"
                />
              </div>
            )}

            {appearance.backgroundType === 'image' && (
              <div className="mt-4 space-y-3">
                <div className="flex gap-2">
                  <input
                    ref={imageFile}
                    type="file"
                    accept="image/*"
                    hidden
                    onChange={(event) => importImage(event.target.files?.[0] ?? null)}
                  />
                  <button type="button" className="btn-secondary btn-sm" onClick={() => imageFile.current?.click()}>
                    选择本地图片
                  </button>
                  {appearance.imageUrl && (
                    <button
                      type="button"
                      className="btn-secondary btn-sm"
                      onClick={() => update({ imageUrl: '' })}
                    >
                      清除图片
                    </button>
                  )}
                </div>
                <input
                  value={appearance.imageUrl.startsWith('data:') ? '' : appearance.imageUrl}
                  onChange={(event) => update({ imageUrl: event.target.value })}
                  placeholder="或粘贴图片 URL"
                  className="input"
                />
              </div>
            )}

            {appearance.backgroundType === 'image' && (
              <div className="mt-4 space-y-3">
                <label className="block text-xs text-stone-500">
                  模糊 {appearance.blur}px
                  <input
                    type="range"
                    min={0}
                    max={20}
                    value={appearance.blur}
                    onChange={(event) => update({ blur: Number(event.target.value) })}
                    className="mt-1 w-full"
                    style={{ accentColor: 'var(--accent)' }}
                  />
                </label>
                <label className="block text-xs text-stone-500">
                  暗化 {Math.round(appearance.dim * 100)}%
                  <input
                    type="range"
                    min={0}
                    max={80}
                    value={Math.round(appearance.dim * 100)}
                    onChange={(event) => update({ dim: Number(event.target.value) / 100 })}
                    className="mt-1 w-full"
                    style={{ accentColor: 'var(--accent)' }}
                  />
                </label>
              </div>
            )}
          </section>

          <section>
            <h3 className="text-xs font-medium uppercase tracking-wide text-stone-400">自定义 CSS</h3>
            <textarea
              value={cssDraft}
              onChange={(event) => setCssDraft(event.target.value)}
              rows={9}
              spellCheck={false}
              placeholder={'/* 覆盖任意组件样式，例如 */\n.card { border-radius: 6px; }\n/* 也支持 @import url("https://.../theme.css"); */'}
              className="input mt-3 resize-y font-mono text-xs leading-relaxed"
            />
            <div className="mt-2 flex flex-wrap gap-2">
              <button type="button" className="btn-primary btn-sm" onClick={applyCss}>
                应用
              </button>
              <input
                ref={cssFile}
                type="file"
                accept=".css,text/css"
                hidden
                onChange={(event) => importCssFile(event.target.files?.[0] ?? null)}
              />
              <button type="button" className="btn-secondary btn-sm" onClick={() => cssFile.current?.click()}>
                从文件导入
              </button>
              <button
                type="button"
                className="btn-secondary btn-sm"
                onClick={() => {
                  setCssDraft('')
                  update({ customCss: '' })
                }}
              >
                清空
              </button>
            </div>
          </section>
        </div>

        <div className="border-t border-stone-100 px-5 py-4">
          <button
            type="button"
            className="btn-secondary w-full"
            onClick={() => {
              reset()
              toast.info('已恢复默认外观')
            }}
          >
            恢复默认
          </button>
        </div>
      </div>
    </aside>
  )
}
