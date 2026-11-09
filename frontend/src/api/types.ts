// Mirrors the backend DTOs (see backend/src/main/java/com/cinebot/**/dto). Money is always integer paise.

export type Role = 'USER' | 'ADMIN'

export interface User { id: number; email: string; fullName: string; phone: string | null; role: Role }

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
  user: User
}

export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

export interface Genre { id: number; name: string }

export interface MovieSummary {
  id: number; title: string; posterUrl: string | null; releaseDate: string | null; language: string | null
  runtimeMinutes: number | null; rating: number | null; genres: string[]
}

export interface MovieDetail {
  id: number; tmdbId: number; title: string; originalTitle: string | null; overview: string | null
  language: string | null; releaseDate: string | null; runtimeMinutes: number | null; posterUrl: string | null
  backdropUrl: string | null; rating: number | null; genres: Genre[]
}

export type SeatType = 'REGULAR' | 'PREMIUM' | 'RECLINER'
export type SeatStatus = 'AVAILABLE' | 'LOCKED' | 'BOOKED' | 'BLOCKED'

export interface Show {
  id: number; movieId: number; movieTitle: string; theatreId: number; theatreName: string; city: string
  screenId: number; screenName: string; screenType: string; startTime: string; endTime: string
  language: string; format: string; status: 'SCHEDULED' | 'CANCELLED'; totalSeats: number; availableSeats: number
  minPricePaise: number | null; prices?: { seatType: SeatType; pricePaise: number }[]
}

export interface SeatView { showSeatId: number; label: string; number: number; type: SeatType; pricePaise: number; status: SeatStatus }
export interface SeatMap { showId: number; totalSeats: number; availableSeats: number; rows: { label: string; seats: SeatView[] }[] }

export interface SeatLock {
  lockId: string; showId: number; status: string; expiresAt: string
  seats: { showSeatId: number; label: string; type: SeatType; pricePaise: number }[]; totalPaise: number
}

export type BookingStatus =
  | 'PENDING' | 'PAYMENT_PENDING' | 'CONFIRMED' | 'PAYMENT_FAILED' | 'EXPIRED' | 'CANCELLED' | 'REFUND_PENDING' | 'REFUNDED'

export interface Booking {
  id: number; reference: string; status: BookingStatus
  show: { showId: number; movieId: number; movieTitle: string; theatreName: string; city: string; screenName: string; startTime: string } | null
  seats: { showSeatId: number; label: string; type: SeatType; pricePaise: number }[]
  totalPaise: number; currency: string; seatLockId: string; expiresAt: string; createdAt: string
  confirmedAt?: string; ticket?: { qrPayload: string }
}

export interface PaymentOrder {
  paymentId: number; bookingId: number; provider: string; orderId: string; amountPaise: number; currency: string
  status: string; expiresAt: string; checkoutUrl: string
}

export interface CheckoutResult { orderId: string; paymentId: string; status: 'CAPTURED' | 'FAILED'; signature: string | null; failureReason: string | null }

export type CardType = 'MOVIES' | 'MOVIE_DETAILS' | 'THEATRES' | 'SHOWS' | 'SEAT_MAP' | 'HOLD_CONFIRMATION_REQUIRED'
  | 'CANCEL_CONFIRMATION_REQUIRED' | 'BOOKING_SUMMARY' | 'PAYMENT_CONFIRMATION_REQUIRED' | 'TICKET' | 'BOOKINGS'
export interface ChatCard { type: CardType; data: unknown }
export interface ChatReply { conversationId: string; response: string; cards: ChatCard[] }
export interface ChatConversation {
  conversationId: string; createdAt: string
  messages: { role: 'USER' | 'ASSISTANT'; content: string; cards: ChatCard[] | null; createdAt: string }[]
}

export interface Notification { id: number; kind: string; subject: string; body: string; createdAt: string }

/** RFC 7807 body returned by every backend error. Extra fields (e.g. unavailableSeatIds) vary by code. */
export interface Problem {
  type?: string; title?: string; status: number; detail?: string; code?: string; traceId?: string
  errors?: { field: string; message: string }[]
  [extra: string]: unknown
}
