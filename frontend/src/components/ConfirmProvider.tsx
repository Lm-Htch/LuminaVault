import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'

export interface ConfirmOptions {
  title: string
  message?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
}

type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>

const ConfirmContext = createContext<ConfirmFn | null>(null)

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ options: ConfirmOptions; resolve: (value: boolean) => void } | null>(null)

  const confirm = useCallback<ConfirmFn>(
    (options) => new Promise<boolean>((resolve) => setState({ options, resolve })),
    [],
  )

  const close = useCallback((value: boolean) => {
    setState((current) => {
      current?.resolve(value)
      return null
    })
  }, [])

  useEffect(() => {
    if (!state) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close(false)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [state, close])

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {state && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center px-4">
          <div
            className="modal-backdrop animate-fade-in absolute inset-0 backdrop-blur-sm"
            onClick={() => close(false)}
          />
          <div className="modal-panel scale-in relative w-full max-w-sm rounded-2xl border border-stone-200 p-6 shadow-2xl">
            <h3 className="text-base font-semibold text-stone-900">{state.options.title}</h3>
            {state.options.message && (
              <p className="mt-2 text-sm leading-relaxed text-stone-500">{state.options.message}</p>
            )}
            <div className="mt-6 flex justify-end gap-3">
              <button type="button" className="btn-secondary" onClick={() => close(false)}>
                {state.options.cancelText ?? '取消'}
              </button>
              <button
                type="button"
                className={state.options.danger ? 'btn-danger' : 'btn-primary'}
                onClick={() => close(true)}
              >
                {state.options.confirmText ?? '确定'}
              </button>
            </div>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  )
}

export function useConfirm(): ConfirmFn {
  const context = useContext(ConfirmContext)
  if (!context) {
    throw new Error('useConfirm 必须在 ConfirmProvider 内使用')
  }
  return context
}
