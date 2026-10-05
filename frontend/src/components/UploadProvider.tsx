import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react'
import { uploadWithProgress } from '../api/client'
import type { AssetResponse } from '../api/types'
import { UploadDrawer, type UploadItem } from './UploadDrawer'
import { useToast } from './ToastProvider'

interface UploadContextValue {
  uploading: boolean
  revision: number
  uploadFiles: (files: FileList | File[], folder?: { id: number; name: string }) => void
  clearFinished: () => void
}

const UploadContext = createContext<UploadContextValue | null>(null)

export function useUploads(): UploadContextValue {
  const context = useContext(UploadContext)
  if (!context) {
    throw new Error('useUploads must be used within UploadProvider')
  }
  return context
}

export function UploadProvider({ children }: { children: ReactNode }) {
  const toast = useToast()
  const [items, setItems] = useState<UploadItem[]>([])
  const [open, setOpen] = useState(true)
  const [revision, setRevision] = useState(0)
  const nextId = useRef(1)

  const patch = useCallback((id: number, update: Partial<UploadItem>) => {
    setItems((current) => {
      const target = current.find((item) => item.id === id)
      if (!target) {
        return current
      }
      if (update.status === undefined && update.percent !== undefined && target.percent === update.percent) {
        return current
      }
      return current.map((item) => (item.id === id ? { ...item, ...update } : item))
    })
  }, [])

  const uploadFiles = useCallback(
    (input: FileList | File[], folder?: { id: number; name: string }) => {
      const files = Array.from(input)
      if (files.length === 0) {
        return
      }
      const batch = files.map((file) => ({ id: nextId.current++, file }))
      setItems((current) => [
        ...batch.map(({ id, file }) => ({ id, name: file.name, percent: 0, status: 'uploading' as const })),
        ...current,
      ])
      setOpen(true)
      void (async () => {
        let okCount = 0
        let failCount = 0
        for (const { id, file } of batch) {
          const form = new FormData()
          form.append('file', file)
          const path = folder ? `/api/assets?folderId=${folder.id}` : '/api/assets'
          try {
            await uploadWithProgress<AssetResponse>(path, form, (percent) => patch(id, { percent }))
            okCount++
            patch(id, { status: 'done', percent: 100 })
          } catch (err) {
            failCount++
            patch(id, { status: 'error', error: err instanceof Error ? err.message : '上传失败' })
          }
        }
        if (okCount > 0) {
          toast.success(
            folder ? `已上传 ${okCount} 张到「${folder.name}」` : `已上传 ${okCount} 张，已交给 Agent 自动分类`,
          )
          setRevision((current) => current + 1)
        }
        if (failCount > 0) {
          toast.error(`有 ${failCount} 张上传失败`)
        }
      })()
    },
    [patch, toast],
  )

  const clearFinished = useCallback(() => {
    setItems((current) => current.filter((item) => item.status === 'uploading'))
  }, [])

  const uploading = items.some((item) => item.status === 'uploading')

  return (
    <UploadContext.Provider value={{ uploading, revision, uploadFiles, clearFinished }}>
      {children}
      <UploadDrawer
        items={items}
        open={open}
        onOpen={() => setOpen(true)}
        onClose={() => setOpen(false)}
        onClearFinished={clearFinished}
      />
    </UploadContext.Provider>
  )
}
