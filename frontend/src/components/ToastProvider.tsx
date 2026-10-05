import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

type ToastType = 'success' | 'error' | 'info'

interface ToastItem {
  id: number
  type: ToastType
  text: string
}

interface ToastApi {
  success: (text: string) => void
  error: (text: string) => void
  info: (text: string) => void
}

const ToastContext = createContext<ToastApi | null>(null)

const styles: Record<ToastType, string> = {
  success: 'border-teal-200 text-teal-800',
  error: 'border-red-200 text-red-700',
  info: 'border-stone-200 text-stone-700',
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const push = useCallback((type: ToastType, text: string) => {
    const id = Date.now() + Math.random()
    setItems((current) => [...current, { id, type, text }])
    window.setTimeout(() => {
      setItems((current) => current.filter((item) => item.id !== id))
    }, 3500)
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      success: (text: string) => push('success', text),
      error: (text: string) => push('error', text),
      info: (text: string) => push('info', text),
    }),
    [push],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="pointer-events-none fixed right-5 top-5 z-[60] flex w-80 flex-col gap-2">
        {items.map((item) => (
          <div
            key={item.id}
            className={`toast-in toast-surface pointer-events-auto rounded-xl border px-4 py-3 text-sm shadow-lg ${styles[item.type]}`}
          >
            {item.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast(): ToastApi {
  const context = useContext(ToastContext)
  if (!context) {
    throw new Error('useToast 必须在 ToastProvider 内使用')
  }
  return context
}
