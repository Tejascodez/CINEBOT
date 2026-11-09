import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { refreshSession, tokens } from '../api/client'
import { authApi } from '../api/endpoints'
import type { AuthResponse, User } from '../api/types'

interface AuthState {
  user: User | null
  ready: boolean // false until the stored session has been restored (or found missing)
  login: (email: string, password: string) => Promise<void>
  register: (input: { email: string; password: string; fullName: string; phone?: string }) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    tokens.onChange((auth: AuthResponse | null) => setUser(auth?.user ?? null))
    // On reload the access token is gone (memory only): trade the stored refresh token for a new session.
    ;(tokens.hasRefreshToken() ? refreshSession() : Promise.resolve(false)).finally(() => setReady(true))
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    tokens.setSession(await authApi.login({ email, password }))
  }, [])

  const register = useCallback(async (input: { email: string; password: string; fullName: string; phone?: string }) => {
    tokens.setSession(await authApi.register(input))
  }, [])

  const logout = useCallback(async () => {
    const refreshToken = tokens.refreshToken()
    tokens.setSession(null)
    if (refreshToken) await authApi.logout(refreshToken).catch(() => undefined) // revoke server-side, best effort
  }, [])

  const value = useMemo(() => ({ user, ready, login, register, logout }), [user, ready, login, register, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
