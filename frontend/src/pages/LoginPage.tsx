import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { PasswordInput } from '../components/PasswordInput'
import { useToast } from '../components/ToastProvider'

export function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  if (user) {
    return <Navigate to="/" replace />
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    try {
      await login(username.trim(), password)
      navigate('/', { replace: true })
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '登录失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex flex-col items-center gap-3">
          <span className="brand-accent flex h-12 w-12 items-center justify-center rounded-2xl text-2xl font-semibold text-white">
            琅
          </span>
          <div className="text-center">
            <h1 className="text-xl font-semibold text-stone-900">琅嬛 LuminaVault</h1>
            <p className="mt-1 text-sm text-stone-500">个人图片资产库</p>
          </div>
        </div>

        <form onSubmit={submit} className="card p-7">
          <label className="block">
            <span className="label">用户名</span>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              placeholder="请输入用户名"
              className="input"
            />
          </label>
          <label className="mt-5 block">
            <span className="label">密码</span>
            <PasswordInput
              value={password}
              onChange={setPassword}
              autoComplete="current-password"
              placeholder="请输入密码"
            />
          </label>

          <button type="submit" disabled={busy} className="btn-primary mt-6 w-full">
            {busy ? '登录中…' : '登录'}
          </button>
          <p className="mt-5 text-center text-sm text-stone-500">
            还没有账号？{' '}
            <Link to="/register" className="font-medium text-teal-700 hover:underline">
              注册
            </Link>
          </p>
        </form>
      </div>
    </div>
  )
}
