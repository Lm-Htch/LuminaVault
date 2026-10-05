import type { Result, TokenResponse } from './types'

const ACCESS_KEY = 'lumina.accessToken'
const REFRESH_KEY = 'lumina.refreshToken'

export const tokenStore = {
  get access(): string | null {
    return localStorage.getItem(ACCESS_KEY)
  },
  get refresh(): string | null {
    return localStorage.getItem(REFRESH_KEY)
  },
  set(tokens: TokenResponse): void {
    localStorage.setItem(ACCESS_KEY, tokens.accessToken)
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
  },
  clear(): void {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

let refreshing: Promise<boolean> | null = null

export async function refreshTokens(): Promise<boolean> {
  const refreshToken = tokenStore.refresh
  if (!refreshToken) {
    return false
  }
  if (!refreshing) {
    refreshing = fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
      .then(async (response) => {
        if (!response.ok) {
          return false
        }
        const body = (await response.json()) as Result<TokenResponse>
        if (body.code !== 200 || !body.data) {
          return false
        }
        tokenStore.set(body.data)
        return true
      })
      .catch(() => false)
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  const access = tokenStore.access
  if (access) {
    headers.set('Authorization', `Bearer ${access}`)
  }
  const response = await fetch(path, { ...init, headers })
  if (response.status === 401 && retry && !path.startsWith('/api/auth/')) {
    if (await refreshTokens()) {
      return request<T>(path, init, false)
    }
    tokenStore.clear()
  }
  let body: Result<T> | undefined
  try {
    body = (await response.json()) as Result<T>
  } catch {
    body = undefined
  }
  if (!response.ok || !body || body.code !== 200) {
    if (response.status === 401) {
      tokenStore.clear()
    }
    throw new ApiError(response.status, body?.message ?? `请求失败（${response.status}）`)
  }
  return body.data
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  upload: <T>(path: string, form: FormData) => request<T>(path, { method: 'POST', body: form }),
}

export function uploadWithProgress<T>(
  path: string,
  form: FormData,
  onProgress?: (percent: number) => void,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const send = (retry: boolean) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', path)
      const access = tokenStore.access
      if (access) {
        xhr.setRequestHeader('Authorization', `Bearer ${access}`)
      }
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && onProgress) {
          onProgress(Math.round((event.loaded / event.total) * 100))
        }
      }
      xhr.onerror = () => reject(new ApiError(0, '网络错误，上传失败'))
      xhr.onload = async () => {
        if (xhr.status === 401 && retry) {
          if (await refreshTokens()) {
            send(false)
            return
          }
          tokenStore.clear()
        }
        let body: Result<T> | undefined
        try {
          body = JSON.parse(xhr.responseText) as Result<T>
        } catch {
          body = undefined
        }
        if (xhr.status < 200 || xhr.status >= 300 || !body || body.code !== 200) {
          reject(new ApiError(xhr.status, body?.message ?? `上传失败（${xhr.status}）`))
          return
        }
        resolve(body.data)
      }
      xhr.send(form)
    }
    send(true)
  })
}
