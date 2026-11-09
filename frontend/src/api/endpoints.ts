import { api } from './client'
import type {
  AuthResponse, Booking, ChatConversation, ChatReply, CheckoutResult, Genre, MovieDetail, MovieSummary, Notification,
  Page, PaymentOrder, SeatLock, SeatMap, Show, User,
} from './types'

export const authApi = {
  register: (body: { email: string; password: string; fullName: string; phone?: string }) =>
    api<AuthResponse>('/api/v1/auth/register', { body, auth: false }),
  login: (body: { email: string; password: string }) => api<AuthResponse>('/api/v1/auth/login', { body, auth: false }),
  logout: (refreshToken: string) => api<void>('/api/v1/auth/logout', { body: { refreshToken }, auth: false }),
  me: () => api<User>('/api/v1/users/me'),
}

export const catalogApi = {
  movies: (q: { city?: string; genreId?: number; language?: string; sort?: string; page?: number; size?: number }) =>
    api<Page<MovieSummary>>('/api/v1/movies', { query: q }),
  search: (q: string) => api<Page<MovieSummary>>('/api/v1/movies/search', { query: { q, size: 20 } }),
  movie: (id: number) => api<MovieDetail>(`/api/v1/movies/${id}`),
  genres: () => api<Genre[]>('/api/v1/genres'),
  shows: (q: { movieId?: number; theatreId?: number; city?: string; date?: string; from?: string; to?: string }) =>
    api<Show[]>('/api/v1/shows', { query: q }),
  show: (id: number) => api<Show>(`/api/v1/shows/${id}`),
  seats: (showId: number) => api<SeatMap>(`/api/v1/shows/${showId}/seats`),
}

export const bookingApi = {
  lockSeats: (showId: number, showSeatIds: number[]) =>
    api<SeatLock>('/api/v1/seat-locks', { body: { showId, showSeatIds } }),
  releaseLock: (lockId: string) => api<void>(`/api/v1/seat-locks/${lockId}`, { method: 'DELETE' }),
  create: (lockId: string) => api<Booking>('/api/v1/bookings', { body: { lockId } }),
  get: (id: number) => api<Booking>(`/api/v1/bookings/${id}`),
  mine: (page = 0) => api<Page<Booking>>('/api/v1/bookings/my', { query: { page, size: 20 } }),
  cancel: (id: number) => api<Booking>(`/api/v1/bookings/${id}/cancel`, { method: 'POST' }),
}

export const paymentApi = {
  createOrder: (bookingId: number) => api<PaymentOrder>('/api/v1/payments/orders', { body: { bookingId } }),
  checkout: (orderId: string, outcome: 'SUCCESS' | 'FAILURE') =>
    api<CheckoutResult>('/api/v1/payments/simulator/checkout', { body: { orderId, outcome } }),
  verify: (body: { orderId: string; paymentId: string; signature: string }) =>
    api<Booking>('/api/v1/payments/verify', { body }),
}

export const chatApi = {
  conversation: (id: string) => api<ChatConversation>(`/api/v1/agent/conversations/${id}`),
  /**
   * Omit conversationId to start a new conversation; the reply returns the id to use from then on.
   * declineConfirmationId (a card's "No" button) makes the backend withdraw that pending hold/cancellation.
   */
  send: (conversationId: string | null, message: string, declineConfirmationId?: string) =>
    api<ChatReply>('/api/v1/agent/chat', { body: { conversationId: conversationId ?? undefined, message, declineConfirmationId } }),
}

export const notificationApi = {
  mine: () => api<Notification[]>('/api/v1/notifications/my'),
}

export const adminApi = {
  outboxSummary: () => api<{ pending: number; dead: number; done: number }>('/api/v1/admin/outbox/summary'),
  deadEvents: () =>
    api<{ id: string; type: string; aggregateId: string; attempts: number; lastError: string; createdAt: string }[]>(
      '/api/v1/admin/outbox/dead'),
  retryEvent: (id: string) => api<void>(`/api/v1/admin/outbox/${id}/retry`, { method: 'POST' }),
  syncMovies: () =>
    api<{ id: number; status: string; moviesUpserted: number; error: string | null }>('/api/v1/admin/movies/sync', { method: 'POST' }),
  syncRuns: () =>
    api<{ id: number; status: string; triggeredBy: string; startedAt: string; moviesUpserted: number; error: string | null }[]>(
      '/api/v1/admin/movies/sync-runs'),
  showStats: (showId: number) =>
    api<{ showId: number; bookingsConfirmed: number; seatsSold: number; revenuePaise: number; refunds: number }>(
      `/api/v1/admin/stats/shows/${showId}`),
  verifyTicket: (qrPayload: string) =>
    api<{ valid: boolean; reference: string | null; status: string | null; seats: string[]; reason: string | null }>(
      '/api/v1/admin/tickets/verify', { body: { qrPayload } }),
  faults: () => api<{ crashAfterCapture: boolean }>('/api/v1/admin/payment-faults'),
  setFaults: (crashAfterCapture: boolean) =>
    api<{ crashAfterCapture: boolean }>('/api/v1/admin/payment-faults', { body: { crashAfterCapture } }),
}
