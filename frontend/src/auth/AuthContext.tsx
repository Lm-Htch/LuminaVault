import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, tokenStore } from '../api/client'
import type { TokenResponse, UserResponse } from '../api/types'

interface AuthContextValue {
  user: UserResponse | null
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  register: (username: string, password: string, nickname?: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!tokenStore.access) {
      setLoading(false)
      return
    }
    api
      .get<UserResponse>('/api/auth/me')
      .then(setUser)
      .catch(() => tokenStore.clear())
      .finally(() => setLoading(false))
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    const tokens = await api.post<TokenResponse>('/api/auth/login', { username, password })
    tokenStore.set(tokens)
    setUser(await api.get<UserResponse>('/api/auth/me'))
  }, [])

  const register = useCallback(async (username: string, password: string, nickname?: string) => {
    const tokens = await api.post<TokenResponse>('/api/auth/register', {
      username,
      password,
      nickname: nickname || undefined,
    })
    tokenStore.set(tokens)
    setUser(await api.get<UserResponse>('/api/auth/me'))
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.post('/api/auth/logout', { refreshToken: tokenStore.refresh })
    } catch {
      // 忽略登出失败
    }
    tokenStore.clear()
    setUser(null)
  }, [])

  const value = useMemo(
    () => ({ user, loading, login, register, logout }),
    [user, loading, login, register, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth 必须在 AuthProvider 内使用')
  }
  return context
}
