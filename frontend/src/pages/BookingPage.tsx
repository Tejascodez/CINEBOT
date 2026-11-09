import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { QRCodeSVG } from 'qrcode.react'
import { useNavigate, useParams } from 'react-router-dom'
import { bookingApi, paymentApi } from '../api/endpoints'
import type { Booking } from '../api/types'
import { Countdown } from '../components/Countdown'
import { ErrorBanner, Spinner } from '../components/Feedback'
import { StatusBadge } from '../components/StatusBadge'
import { dateTime, money } from '../lib/format'

/**
 * One page per booking, adapting to its state: summary + Pay while unpaid, ticket with QR once confirmed,
 * an explanation otherwise. While PAYMENT_PENDING it polls, because the outcome may arrive by webhook
 * (e.g. the customer closed the checkout tab).
 */
export function BookingPage() {
  const id = Number(useParams().id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const booking = useQuery({
    queryKey: ['booking', id],
    queryFn: () => bookingApi.get(id),
    refetchInterval: (q) => (q.state.data?.status === 'PAYMENT_PENDING' ? 2000 : false),
  })

  const pay = useMutation({
    mutationFn: () => paymentApi.createOrder(id),
    // The provider's hosted checkout; with the simulator it is a page of this app.
    onSuccess: (order) => navigate(`${order.checkoutUrl}?bookingId=${id}`, { state: { order } }),
  })
  const cancel = useMutation({
    mutationFn: () => bookingApi.cancel(id),
    onSuccess: (b) => queryClient.setQueryData(['booking', id], b),
  })

  if (booking.isPending) return <Spinner />
  if (booking.error) return <ErrorBanner error={booking.error} />
  const b = booking.data
  const unpaid = b.status === 'PENDING' || b.status === 'PAYMENT_PENDING'

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <div className="flex items-center gap-3">
        <h1 className="text-2xl font-bold">{b.status === 'CONFIRMED' ? 'Your tickets' : 'Booking summary'}</h1>
        <StatusBadge status={b.status} />
        <span className="ml-auto font-mono text-sm text-slate-400">{b.reference}</span>
      </div>

      <Details booking={b} />

      {unpaid && (
        <div className="card space-y-4">
          <p className="text-sm text-slate-300">
            Seats are held for you for <Countdown until={b.expiresAt} onExpire={() => void booking.refetch()} />.
            {b.status === 'PAYMENT_PENDING' && ' Waiting for the payment result…'}
          </p>
          <div className="flex flex-wrap gap-3">
            <button className="btn-primary" disabled={pay.isPending} onClick={() => pay.mutate()}>
              {b.status === 'PAYMENT_PENDING' ? 'Resume payment' : `Pay ${money(b.totalPaise)}`}
            </button>
            <button className="btn-ghost" disabled={cancel.isPending} onClick={() => cancel.mutate()}>Cancel booking</button>
          </div>
          <ErrorBanner error={pay.error ?? cancel.error} />
        </div>
      )}

      {b.status === 'CONFIRMED' && b.ticket && (
        <div className="card flex flex-col items-center gap-3">
          <div className="rounded-xl bg-white p-4">
            <QRCodeSVG value={b.ticket.qrPayload} size={200} aria-label="Ticket QR code" />
          </div>
          <p className="text-sm text-slate-400">Show this at the entrance.</p>
          <button className="btn-ghost text-xs" disabled={cancel.isPending} onClick={() => {
            if (confirm('Cancel and get a refund? Allowed until 2 hours before the show.')) cancel.mutate()
          }}>Cancel & refund</button>
          <ErrorBanner error={cancel.error} />
        </div>
      )}

      <Outcome status={b.status} />
    </div>
  )
}

function Details({ booking: b }: { booking: Booking }) {
  return (
    <div className="card grid gap-2 text-sm sm:grid-cols-2">
      {b.show && (
        <>
          <p className="sm:col-span-2 text-lg font-semibold">{b.show.movieTitle}</p>
          <p className="text-slate-300">{b.show.theatreName} · {b.show.screenName}</p>
          <p className="text-slate-300">{dateTime(b.show.startTime)}</p>
        </>
      )}
      <p>Seats <span className="font-semibold">{b.seats.map((s) => s.label).join(', ')}</span></p>
      <p>Total <span className="font-semibold">{money(b.totalPaise)}</span></p>
    </div>
  )
}

const OUTCOMES: Partial<Record<Booking['status'], string>> = {
  PAYMENT_FAILED: 'The payment did not go through and your seats were released. You can choose seats again.',
  EXPIRED: 'The seat hold expired before payment completed.',
  CANCELLED: 'This booking was cancelled.',
  REFUND_PENDING: 'A refund has been initiated. If your payment arrived after the seats were released, you get the full amount back.',
  REFUNDED: 'Your refund has been processed.',
}

function Outcome({ status }: { status: Booking['status'] }) {
  const text = OUTCOMES[status]
  return text ? <p className="card text-sm text-slate-300">{text}</p> : null
}
