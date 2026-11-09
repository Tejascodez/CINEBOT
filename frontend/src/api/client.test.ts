import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, tokens } from './client'

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const session = (n: number) => ({
  accessToken: `access-${n}`, refreshToken: `refresh-${n}`, tokenType: 'Bearer', expiresIn: 900,
  user: { id: 1, email: 'a@b.c', fullName: 'A', phone: null, role: 'USER' },
})

describe('api client', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    localStorage.clear()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('maps problem+json errors to ApiError with code and extra fields', async () => {
    fetchMock.mockResolvedValue(json(409, { status: 409, code: 'SEATS_UNAVAILABLE', detail: 'A1 taken', unavailableSeatIds: [7] }))

    const error = (await api('/api/v1/seat-locks', { body: {} }).catch((e: unknown) => e)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('SEATS_UNAVAILABLE')
    expect(error.message).toBe('A1 taken')
    expect(error.problem.unavailableSeatIds).toEqual([7])
  })

  it('refreshes once and retries when the access token expired', async () => {
    tokens.setSession(session(1) as never)
    fetchMock
      .mockResolvedValueOnce(json(401, { status: 401, code: 'UNAUTHORIZED' }))
      .mockResolvedValueOnce(json(200, session(2)))
      .mockResolvedValueOnce(json(200, { ok: true }))

    await expect(api('/api/v1/bookings/my')).resolves.toEqual({ ok: true })

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/v1/auth/refresh')
    expect(fetchMock.mock.calls[2]![1].headers.Authorization).toBe('Bearer access-2')
    expect(localStorage.getItem('cinebot.refreshToken')).toBe('refresh-2')
  })

  it('shares ONE refresh between concurrent 401s (a second refresh would look like token theft)', async () => {
    tokens.setSession(session(1) as never)
    let refreshes = 0
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url === '/api/v1/auth/refresh') {
        refreshes++
        await new Promise((r) => setTimeout(r, 10))
        return json(200, session(2))
      }
      const auth = (init.headers as Record<string, string>).Authorization
      return auth === 'Bearer access-2' ? json(200, { url }) : json(401, { status: 401 })
    })

    const results = await Promise.all([api('/a'), api('/b'), api('/c')])

    expect(refreshes).toBe(1)
    expect(results).toEqual([{ url: '/a' }, { url: '/b' }, { url: '/c' }])
  })

  it('signs out when the refresh token is rejected', async () => {
    tokens.setSession(session(1) as never)
    fetchMock
      .mockResolvedValueOnce(json(401, { status: 401 }))
      .mockResolvedValueOnce(json(401, { status: 401, code: 'REFRESH_TOKEN_REUSED' }))

    await expect(api('/api/v1/bookings/my')).rejects.toBeInstanceOf(ApiError)
    expect(tokens.hasRefreshToken()).toBe(false)
  })

  it('omits empty query parameters', async () => {
    fetchMock.mockResolvedValue(json(200, []))
    await api('/api/v1/shows', { query: { city: 'Chennai', date: undefined, from: '' } })
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/shows?city=Chennai')
  })
})
