import { useState } from 'react'

interface PasswordInputProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  autoComplete?: string
}

export function PasswordInput({ value, onChange, placeholder, autoComplete }: PasswordInputProps) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="relative">
      <input
        type={visible ? 'text' : 'password'}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        autoComplete={autoComplete}
        className="input pr-12"
      />
      <button
        type="button"
        onClick={() => setVisible((current) => !current)}
        aria-label={visible ? '隐藏密码' : '显示密码'}
        className="absolute inset-y-0 right-0 flex w-12 items-center justify-center text-xs text-stone-400 transition hover:text-teal-700"
      >
        {visible ? '隐藏' : '显示'}
      </button>
    </div>
  )
}
