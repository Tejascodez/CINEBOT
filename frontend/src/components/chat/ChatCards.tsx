import { useMutation } from '@tanstack/react-query'
import { QRCodeSVG } from 'qrcode.react'
import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { paymentApi } from '../../api/endpoints'
import type { Booking, ChatCard, MovieDetail, MovieSummary, SeatMap, Show } from '../../api/types'
import { useCity } from '../../lib/city'
import { dateTime, istDate, languageName, money, runtime, time } from '../../lib/format'
import { Countdown } from '../Countdown'
import { ErrorBanner } from '../Feedback'
import { Poster } from '../Poster'
import { SeatMapView } from '../SeatMapView'
import { StatusBadge } from '../StatusBadge'

type Say = (text: string, declineConfirmationId?: string) => void

interface CardProps {
  card: ChatCard
  say: Say
  onNavigate?: () => void
  /** False once the conversation has moved on: a yes/no on an old card would answer a question no longer open. */
  live?: boolean
}

const dot = (parts: (string | null | undefined)[]) => parts.filter(Boolean).join(' · ')

/** Cards are clickable shortcuts that send the next request to the agent in plain language. */
export function CardView({ card, say, onNavigate, live = true }: CardProps) {
  switch (card.type) {
    case 'MOVIES': return <MoviesCard movies={card.data as MovieSummary[]} say={say} />
    case 'MOVIE_DETAILS': return <MovieDetailsCard movie={card.data as MovieDetail} say={say} />
    case 'SHOWS': return <ShowsCard shows={card.data as Show[]} say={say} />
    case 'THEATRES': return <TheatresCard theatres={card.data as TheatreItem[]} say={say} />
    case 'SEAT_MAP': return <SeatMapCard map={(card.data as { seatMap: SeatMap }).seatMap} say={say} />
    case 'HOLD_CONFIRMATION_REQUIRED': return <HoldCard hold={card.data as HoldSummary} say={say} live={live} />
    case 'CANCEL_CONFIRMATION_REQUIRED': return <CancelCard cancel={card.data as CancelSummary} say={say} live={live} />
    case 'BOOKING_SUMMARY': return <BookingCard booking={card.data as Booking} say={say} />
    case 'PAYMENT_CONFIRMATION_REQUIRED': return <PaymentCard payment={card.data as PaymentSummary} onNavigate={onNavigate} />
    case 'TICKET': return <TicketCard booking={card.data as Booking} />
    case 'BOOKINGS': return <BookingsCard bookings={card.data as Booking[]} say={say} />
    default: return null
  }
}

function MoviesCard({ movies, say }: { movies: MovieSummary[]; say: Say }) {
  const [city] = useCity()
  return (
    <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1">
      {movies.map((m) => (
        <button
          key={m.id}
          className="w-24 shrink-0 rounded-lg text-left transition hover:opacity-80 focus-visible:outline-2 focus-visible:outline-rose-500"
          onClick={() => say(`Show me showtimes for ${m.title} in ${city} on ${istDate(0)}`)}
        >
          <Poster url={m.posterUrl} title={m.title} className="[&>span]:text-xs" />
          <span className="mt-1 block truncate text-xs font-semibold text-slate-200">{m.title}</span>
          <span className="block truncate text-[11px] text-slate-500">
            {dot([m.rating != null ? `★ ${m.rating.toFixed(1)}` : null, languageName(m.language)])}
          </span>
        </button>
      ))}
    </div>
  )
}

function MovieDetailsCard({ movie, say }: { movie: MovieDetail; say: Say }) {
  const [city] = useCity()
  return (
    <div className="card flex gap-3 p-3">
      <Poster url={movie.posterUrl} title={movie.title} className="!w-20 shrink-0 self-start [&>span]:text-xs" />
      <div className="min-w-0 text-sm">
        <p className="font-semibold">{movie.title}</p>
        <p className="text-xs text-slate-400">
          {dot([movie.rating != null ? `★ ${movie.rating.toFixed(1)}` : null, runtime(movie.runtimeMinutes), languageName(movie.language)])}
        </p>
        {movie.genres.length > 0 && (
          <div className="mt-1.5 flex flex-wrap gap-1">
            {movie.genres.map((g) => <span key={g.id} className="rounded-full bg-slate-800 px-2 py-0.5 text-[11px] text-slate-300">{g.name}</span>)}
          </div>
        )}
        {movie.overview && <p className="mt-2 line-clamp-4 text-xs text-slate-300">{movie.overview}</p>}
        <button className="btn-secondary mt-2 px-3 py-1.5 text-xs" onClick={() => say(`Show me showtimes for ${movie.title} in ${city} on ${istDate(0)}`)}>
          See showtimes
        </button>
      </div>
    </div>
  )
}

function ShowsCard({ shows, say }: { shows: Show[]; say: Say }) {
  return (
    <ul className="space-y-1.5">
      {shows.map((s) => {
        const soldOut = s.availableSeats === 0
        return (
          <li key={s.id}>
            <button
              className="flex w-full items-center gap-3 rounded-lg border border-slate-800 bg-slate-900/60 px-3 py-2 text-left transition enabled:hover:border-slate-600 enabled:hover:bg-slate-800/60 disabled:opacity-50"
              disabled={soldOut}
              onClick={() => say(`Show me the seats for show ${s.id}`)}
            >
              <span className="w-16 shrink-0 text-sm font-semibold text-rose-300">{time(s.startTime)}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-xs font-medium text-slate-200">{s.theatreName}</span>
                <span className="block truncate text-[11px] text-slate-500">{dot([s.movieTitle, s.screenName, s.format])}</span>
              </span>
              <span className="shrink-0 text-right text-[11px]">
                <span className="block font-medium text-slate-300">{s.minPricePaise != null ? `from ${money(s.minPricePaise)}` : ''}</span>
                <span className={soldOut ? 'text-rose-400' : s.availableSeats <= 10 ? 'text-amber-300' : 'text-slate-500'}>
                  {soldOut ? 'Sold out' : `${s.availableSeats} left`}
                </span>
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

interface TheatreItem { id: number; name: string; address: string }

function TheatresCard({ theatres, say }: { theatres: TheatreItem[]; say: Say }) {
  return (
    <ul className="space-y-1.5">
      {theatres.map((t) => (
        <li key={t.id}>
          <button
            className="w-full rounded-lg border border-slate-800 bg-slate-900/60 px-3 py-2 text-left transition hover:border-slate-600 hover:bg-slate-800/60"
            onClick={() => say(`What's showing at ${t.name}?`)}
          >
            <span className="block text-xs font-medium text-slate-200">{t.name}</span>
            <span className="block text-[11px] text-slate-500">{t.address}</span>
          </button>
        </li>
      ))}
    </ul>
  )
}

function SeatMapCard({ map, say }: { map: SeatMap; say: Say }) {
  const [picked, setPicked] = useState<number[]>([])
  const seats = map.rows.flatMap((r) => r.seats).filter((s) => picked.includes(s.showSeatId))
  const labels = seats.map((s) => s.label).join(', ')
  const total = seats.reduce((sum, s) => sum + s.pricePaise, 0)
  return (
    <div className="card space-y-3 p-3">
      <SeatMapView
        map={map}
        selected={picked}
        compact
        onToggle={(s) => setPicked((p) => p.includes(s.showSeatId) ? p.filter((x) => x !== s.showSeatId) : [...p, s.showSeatId].slice(0, 10))}
      />
      <button className="btn-primary w-full text-xs" disabled={seats.length === 0} onClick={() => say(`Hold seats ${labels} for show ${map.showId}`)}>
        {seats.length === 0 ? 'Select your seats' : `Hold ${labels} · ${money(total)}`}
      </button>
    </div>
  )
}

/**
 * Sends the user's answer as a normal message: the agent still only acts on an explicit yes. "No" also names the
 * confirmation it declines, so the backend withdraws it before the model reads the reply.
 */
function ConfirmButtons({ say, live, confirmationId, yes, no, yesLabel, noLabel }: { say: Say; live: boolean; confirmationId: string; yes: string; no: string; yesLabel: string; noLabel: string }) {
  if (!live) return null
  return (
    <div className="mt-3 flex gap-2">
      <button className="btn-primary flex-1 px-3 text-xs" onClick={() => say(yes)}>{yesLabel}</button>
      <button className="btn-secondary flex-1 px-3 text-xs" onClick={() => say(no, confirmationId)}>{noLabel}</button>
    </div>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="shrink-0 text-slate-500">{label}</dt>
      <dd className="text-right text-slate-200">{children}</dd>
    </div>
  )
}

interface HoldSummary {
  confirmationId: string; movie: string; theatre: string; screen: string; showTime: string; total: string; holdDurationMinutes: number
  seats: { seat: string; type: string; price: string }[]
}

function HoldCard({ hold, say, live }: { hold: HoldSummary; say: Say; live: boolean }) {
  return (
    <div className="card border-amber-800/70 p-3 text-xs">
      <p className="mb-2 text-sm font-semibold">{hold.movie}</p>
      <dl className="space-y-1">
        <Row label="Theatre">{hold.theatre}</Row>
        <Row label="Screen">{hold.screen}</Row>
        <Row label="Show">{hold.showTime}</Row>
        <Row label="Seats">{hold.seats.map((s) => s.seat).join(', ')}</Row>
        <Row label="Total"><span className="font-semibold">{hold.total}</span></Row>
      </dl>
      <p className="mt-2 text-[11px] text-amber-300/90">Nothing is held yet. Seats are kept for {hold.holdDurationMinutes} minutes once you confirm.</p>
      <ConfirmButtons say={say} live={live} confirmationId={hold.confirmationId} yes="Yes, hold these seats" no="No, don't hold them" yesLabel="Hold seats" noLabel="Not now" />
    </div>
  )
}

interface CancelSummary {
  confirmationId: string
  consequence: string
  booking: { reference: string; movie: string | null; theatre: string | null; showTime: string | null; seats: string[]; total: string }
}

function CancelCard({ cancel, say, live }: { cancel: CancelSummary; say: Say; live: boolean }) {
  const b = cancel.booking
  return (
    <div className="card border-rose-900 p-3 text-xs">
      <p className="mb-2 text-sm font-semibold">Cancel {b.movie ?? 'booking'}?</p>
      <dl className="space-y-1">
        <Row label="Booking"><span className="font-mono">{b.reference}</span></Row>
        {b.showTime && <Row label="Show">{b.showTime}</Row>}
        <Row label="Seats">{b.seats.join(', ')}</Row>
        <Row label="Total">{b.total}</Row>
      </dl>
      <p className="mt-2 text-[11px] text-rose-200/90">{cancel.consequence}</p>
      <ConfirmButtons say={say} live={live} confirmationId={cancel.confirmationId} yes="Yes, cancel this booking" no="No, keep my booking" yesLabel="Cancel booking" noLabel="Keep it" />
    </div>
  )
}

function BookingCard({ booking: b, say }: { booking: Booking; say: Say }) {
  return (
    <div className="card p-3 text-sm">
      <div className="flex items-start justify-between gap-2">
        <p className="font-semibold">{b.show?.movieTitle}</p>
        <StatusBadge status={b.status} />
      </div>
      <p className="mt-1 text-xs text-slate-400">{dot([b.show && dateTime(b.show.startTime), b.show?.theatreName])}</p>
      <p className="text-xs text-slate-400">{dot([b.seats.map((s) => s.label).join(', '), money(b.totalPaise)])}</p>
      <p className="mt-1 font-mono text-[11px] text-slate-500">{b.reference}</p>
      {b.status === 'PENDING' && (
        <button className="btn-primary mt-3 w-full text-xs" onClick={() => say(`Proceed to payment for booking ${b.reference}`)}>Proceed to payment</button>
      )}
    </div>
  )
}

interface PaymentSummary { bookingId: number; reference: string; amountPaise: number; holdExpiresAt?: string }

function PaymentCard({ payment: d, onNavigate }: { payment: PaymentSummary; onNavigate?: () => void }) {
  const navigate = useNavigate()
  const [expired, setExpired] = useState(false)
  const pay = useMutation({
    mutationFn: (bookingId: number) => paymentApi.createOrder(bookingId),
    onSuccess: (order) => {
      onNavigate?.()
      navigate(`${order.checkoutUrl}?bookingId=${order.bookingId}`, { state: { order } })
    },
  })
  return (
    <div className="card space-y-2 border-rose-800 p-3">
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="font-mono text-slate-400">{d.reference}</span>
        {d.holdExpiresAt && <span className="text-slate-400">Held for <Countdown until={d.holdExpiresAt} onExpire={() => setExpired(true)} /></span>}
      </div>
      <p className="text-xs text-slate-300">{expired ? 'This seat hold has expired.' : 'Nothing has been charged yet.'}</p>
      <button className="btn-primary w-full" disabled={pay.isPending || expired} onClick={() => pay.mutate(d.bookingId)}>
        Confirm & Pay {money(d.amountPaise)}
      </button>
      <ErrorBanner error={pay.error} />
    </div>
  )
}

function TicketCard({ booking: b }: { booking: Booking }) {
  return (
    <div className="overflow-hidden rounded-xl border border-emerald-900/70 bg-slate-900/60">
      <div className="p-3 text-sm">
        <p className="font-semibold">{b.show?.movieTitle}</p>
        <p className="text-xs text-slate-400">{dot([b.show && dateTime(b.show.startTime), b.show?.theatreName, b.show?.screenName])}</p>
        <p className="mt-1 text-xs text-slate-300">Seats {b.seats.map((s) => s.label).join(', ')}</p>
      </div>
      <div className="flex items-center gap-3 border-t border-dashed border-slate-700 p-3">
        {b.ticket && <div className="rounded-lg bg-white p-1.5"><QRCodeSVG value={b.ticket.qrPayload} size={72} /></div>}
        <div className="min-w-0 text-xs">
          <StatusBadge status={b.status} />
          <p className="mt-1.5 font-mono text-slate-400">{b.reference}</p>
          <p className="text-slate-500">Show this code at the entrance</p>
        </div>
      </div>
    </div>
  )
}

function BookingsCard({ bookings, say }: { bookings: Booking[]; say: Say }) {
  return (
    <ul className="space-y-1.5">
      {bookings.map((b) => (
        <li key={b.id}>
          <button
            className="w-full rounded-lg border border-slate-800 bg-slate-900/60 px-3 py-2 text-left transition hover:border-slate-600 hover:bg-slate-800/60"
            onClick={() => say(`Show me booking ${b.reference}`)}
          >
            <span className="flex items-center justify-between gap-2">
              <span className="truncate text-xs font-medium text-slate-200">{b.show?.movieTitle ?? b.reference}</span>
              <StatusBadge status={b.status} />
            </span>
            <span className="mt-0.5 block truncate text-[11px] text-slate-500">
              {dot([b.show && dateTime(b.show.startTime), b.seats.map((s) => s.label).join(', '), money(b.totalPaise)])}
            </span>
          </button>
        </li>
      ))}
    </ul>
  )
}
