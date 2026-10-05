import { useEffect, useState, type FormEvent } from 'react'

interface FolderDialogProps {
  open: boolean
  title: string
  initialName?: string
  initialDescription?: string
  submitText?: string
  onCancel: () => void
  onSubmit: (name: string, description: string) => void
}

export function FolderDialog({
  open,
  title,
  initialName = '',
  initialDescription = '',
  submitText = '保存',
  onCancel,
  onSubmit,
}: FolderDialogProps) {
  const [name, setName] = useState(initialName)
  const [description, setDescription] = useState(initialDescription)

  useEffect(() => {
    if (open) {
      setName(initialName)
      setDescription(initialDescription)
    }
  }, [open, initialName, initialDescription])

  if (!open) {
    return null
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!name.trim()) {
      return
    }
    onSubmit(name.trim(), description.trim())
  }

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center px-4">
      <div className="modal-backdrop animate-fade-in absolute inset-0 backdrop-blur-sm" onClick={onCancel} />
      <form onSubmit={submit} className="modal-panel scale-in relative w-full max-w-sm rounded-2xl border border-stone-200 p-6 shadow-2xl">
        <h3 className="text-base font-semibold text-stone-900">{title}</h3>
        <label className="mt-4 block">
          <span className="label">文件夹名</span>
          <input
            autoFocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="如：风景、人物、UI素材"
            className="input"
          />
        </label>
        <label className="mt-4 block">
          <span className="label">描述（可选，用于辅助 AI 分类）</span>
          <input
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="如：自然风景、山川湖海"
            className="input"
          />
        </label>
        <div className="mt-6 flex justify-end gap-3">
          <button type="button" className="btn-secondary" onClick={onCancel}>
            取消
          </button>
          <button type="submit" className="btn-primary" disabled={!name.trim()}>
            {submitText}
          </button>
        </div>
      </form>
    </div>
  )
}
