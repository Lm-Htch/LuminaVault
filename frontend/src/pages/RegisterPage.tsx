import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { PasswordInput } from '../components/PasswordInput'
import { useToast } from '../components/ToastProvider'

export function RegisterPage() {
  const { user, register } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [username, setUsername] = useState('')
  const [nickname, setNickname] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)

  if (user) {
    return <Navigate to="/" replace />
  }

  const mismatch = confirm.length > 0 && confirm !== password

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (password !== confirm) {
      toast.error('两次输入的密码不一致')
      return
    }
    setBusy(true)
    try {
      await register(username.trim(), password, nickname.trim())
      navigate('/', { replace: true })
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '注册失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex flex-col items-center gap-3">
          <span className="brand-accent flex h-12 w-12 items-center justify-center rounded-2xl text-2xl font-semibold text-white">
            琅
          </span>
          <div className="text-center">
            <h1 className="text-xl font-semibold text-stone-900">创建账号</h1>
            <p className="mt-1 text-sm text-stone-500">加入你的私人图片库</p>
          </div>
        </div>

        <form onSubmit={submit} className="card p-7">
          <label className="block">
            <span className="label">用户名</span>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="3-32 位字母、数字或下划线"
              autoComplete="username"
              className="input"
            />
          </label>
          <label className="mt-5 block">
            <span className="label">昵称（选填）</span>
            <input
              value={nickname}
              onChange={(event) => setNickname(event.target.value)}
              placeholder="用于界面展示"
              className="input"
            />
          </label>
          <label className="mt-5 block">
            <span className="label">密码</span>
            <PasswordInput
              value={password}
              onChange={setPassword}
              autoComplete="new-password"
              placeholder="至少 8 位"
            />
          </label>
          <label className="mt-5 block">
            <span className="label">确认密码</span>
            <PasswordInput
              value={confirm}
              onChange={setConfirm}
              autoComplete="new-password"
              placeholder="请再次输入密码"
            />
            {mismatch && <p className="mt-2 text-xs text-red-600">两次输入的密码不一致</p>}
          </label>

          <button type="submit" disabled={busy} className="btn-primary mt-6 w-full">
            {busy ? '注册中…' : '注册并登录'}
          </button>
          <p className="mt-5 text-center text-sm text-stone-500">
            已有账号？{' '}
            <Link to="/login" className="font-medium text-teal-700 hover:underline">
              登录
            </Link>
          </p>
        </form>
      </div>
    </div>
  )
}
