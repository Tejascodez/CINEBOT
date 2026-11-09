import type { AuthResponse, Problem } from './types'

/** Thrown for every non-2xx response; carries the backend's problem+json so the UI can switch on `code`. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed (${problem.status})`)
    this.status = problem.status
    this.code = problem.code
    this.problem = problem
  }
}

const REFRESH_KEY = 'cinebot.refreshToken'

/**
 * Token storage: the access token lives only in memory (gone on reload, never in storage), the refresh token in
 * localStorage so a reload stays signed in. Trade-off: localStorage is readable by injected scripts (XSS);
 * an httpOnly refresh cookie would be safer and is a backend change for later. Refresh tokens rotate and
 * reuse is detected server-side, which limits the damage of a stolen one.
 */
let accessToken: string | null = null
let onSessionChange: ((auth: AuthResponse | null) => void) | null = null

export const tokens = {
  setSession(auth: AuthResponse | null) {
    accessToken = auth?.accessToken ?? null
    if (auth) localStorage.setItem(REFRESH_KEY, auth.refreshToken)
    else localStorage.removeItem(REFRESH_KEY)
    onSessionChange?.(auth)
  },
  hasRefreshToken: () => localStorage.getItem(REFRESH_KEY) !== null,
  refreshToken: () => localStorage.getItem(REFRESH_KEY),
  onChange(listener: (auth: AuthResponse | null) => void) {
    onSessionChange = listener
  },
}

/**
 * Single-flight refresh. If several requests get 401 at once, they must share ONE refresh call: the backend
 * rotates refresh tokens and treats a second use of the same token as theft, revoking the whole session.
 */
let refreshInFlight: Promise<boolean> | null = null

export function refreshSession(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const refreshToken = tokens.refreshToken()
      if (!refreshToken) return false
      try {
        const res = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        })
        if (!res.ok) {
          tokens.setSession(null)
          return false
        }
        tokens.setSession((await res.json()) as AuthResponse)
        return true
      } catch {
        return false // network error: keep the refresh token, the user may retry
      } finally {
        refreshInFlight = null
      }
    })()
  }
  return refreshInFlight
}

export interface RequestOptions {
  method?: string
  body?: unknown
  query?: Record<string, string | number | boolean | undefined | null>
  auth?: boolean // default true: attach the bearer token when present
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const url = withQuery(path, options.query)
  const send = () => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (options.body !== undefined) headers['Content-Type'] = 'application/json'
    if (options.auth !== false && accessToken) headers.Authorization = `Bearer ${accessToken}`
    return fetch(url, {
      method: options.method ?? (options.body !== undefined ? 'POST' : 'GET'),
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    })
  }

  let res = await send()
  if (res.status === 401 && options.auth !== false && tokens.hasRefreshToken()) {
    if (await refreshSession()) res = await send() // exactly one retry with the new access token
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  const data: unknown = text ? JSON.parse(text) : undefined
  if (!res.ok) {
    throw new ApiError(
      data && typeof data === 'object' ? (data as Problem) : { status: res.status, detail: res.statusText },
    )
  }
  return data as T
}

function withQuery(path: string, query: RequestOptions['query']): string {
  if (!query) return path
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  }
  const qs = params.toString()
  return qs ? `${path}?${qs}` : path
}
